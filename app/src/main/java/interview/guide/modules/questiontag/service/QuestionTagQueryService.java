package interview.guide.modules.questiontag.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.questiontag.dto.QuestionTagNodeDetailResponse;
import interview.guide.modules.questiontag.dto.QuestionTagNodeResponse;
import interview.guide.modules.questiontag.dto.QuestionTagSearchResponse;
import interview.guide.modules.questiontag.dto.QuestionTaxonomyVersionResponse;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeAliasEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity.Status;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity;
import interview.guide.modules.questiontag.model.QuestionSkillKnowledgeScopeEntity;
import interview.guide.modules.questiontag.model.QuestionTaxonomyVersionEntity;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeAliasRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRelationRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.repository.QuestionSkillKnowledgeScopeRepository;
import interview.guide.modules.questiontag.repository.QuestionTaxonomyVersionRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionTagQueryService {

  private static final int DEFAULT_SEARCH_LIMIT = 20;
  private static final int MAX_SEARCH_LIMIT = 50;

  private final QuestionTaxonomyVersionRepository versionRepository;
  private final QuestionKnowledgeNodeRepository nodeRepository;
  private final QuestionKnowledgeNodeAliasRepository aliasRepository;
  private final QuestionKnowledgeNodeRelationRepository relationRepository;
  private final QuestionSkillKnowledgeScopeRepository scopeRepository;

  public QuestionTaxonomyVersionResponse currentVersion() {
    QuestionTaxonomyVersionEntity version = currentVersionEntity();
    return new QuestionTaxonomyVersionResponse(version.getVersion(), version.getStatus().name(),
        version.getContentHash(), version.getPublishedAt());
  }

  public int currentVersionNumber() {
    return currentVersionEntity().getVersion();
  }

  /** Returns the immutable, version-specific roots used to scope question-generation tools. */
  public List<String> generationRoots(int version, String skillId) {
    if (skillId == null || skillId.isBlank()
        || "custom".equalsIgnoreCase(skillId)
        || "knowledge-base".equalsIgnoreCase(skillId)) {
      requireTaxonomyVersion(version);
      return List.of("TECH_KNOWLEDGE");
    }
    List<String> roots = scopeRepository.findByIdTaxonomyVersionAndIdSkillId(version, skillId)
        .stream().map(scope -> scope.getId().getNodeCode()).distinct().toList();
    if (roots.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "未找到题目知识目录 Skill scope: " + skillId);
    }
    return roots;
  }

  /**
   * Read-only query used by the scoped LLM tools. It never consults the current version implicitly:
   * callers must pass the version and roots captured when generation started.
   */
  public GenerationQueryResponse queryForGeneration(
      int version,
      Set<String> allowedRoots,
      String parentCode,
      List<String> queries,
      Boolean assignableOnly,
      Integer requestedLimit
  ) {
    requireTaxonomyVersion(version);
    if (allowedRoots == null || allowedRoots.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目知识查询范围不能为空");
    }
    if (queries != null && queries.size() > 10) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "最多查询 10 个关键词");
    }
    List<String> safeQueries = queries == null ? List.of() : queries.stream()
        .filter(query -> query != null && !query.isBlank()).map(String::trim).toList();
    if (safeQueries.size() > 10 || safeQueries.stream().anyMatch(query -> query.length() > 100)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "最多查询 10 个关键词，每个关键词不超过 100 字符");
    }
    int limit = requestedLimit == null ? 10 : requestedLimit;
    if (limit < 1 || limit > 20) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每个关键词最多返回 20 个知识点");
    }

    List<QuestionKnowledgeNodeEntity> nodes = activeNodes(version);
    Set<String> scopedCodes = scopedCodes(nodes, List.copyOf(allowedRoots));
    if (parentCode != null && !parentCode.isBlank()) {
      if (!scopedCodes.contains(parentCode) || !isWithinScope(parentCode, allowedRoots)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "父节点不在本次出题允许范围内: " + parentCode);
      }
    }
    Map<String, QuestionKnowledgeNodeEntity> byCode = indexNodes(nodes);
    Map<String, List<String>> aliases = aliasesByCode(version);
    boolean requireAssignable = Boolean.TRUE.equals(assignableOnly);

    if (safeQueries.isEmpty()) {
      String lookupParent = parentCode;
      List<QuestionKnowledgeNodeEntity> roots = nodes.stream()
          .filter(node -> lookupParent != null
              ? lookupParent.equals(node.getParentCode())
              : allowedRoots.stream().anyMatch(root -> root.equals("TECH_KNOWLEDGE")
                  ? node.getParentCode() != null && "TECH_KNOWLEDGE".equals(node.getParentCode())
                  : root.equals(node.getCode())))
          .filter(node -> scopedCodes.contains(node.getCode()))
          .filter(node -> !requireAssignable || node.isAssignable())
          .limit(limit)
          .toList();
      // TECH_KNOWLEDGE is a non-assignable wrapper above the practical taxonomy roots.
      if (lookupParent == null && allowedRoots.contains("TECH_KNOWLEDGE") && roots.isEmpty()) {
        roots = nodes.stream().filter(node -> node.getParentCode() == null)
            .filter(node -> !requireAssignable || node.isAssignable()).limit(limit).toList();
      }
      return new GenerationQueryResponse(version, List.of(new GenerationQueryResult(
          null, roots.stream().map(node -> generationMatch(node, "ROOT", byCode, aliases)).toList())));
    }

    List<GenerationQueryResult> results = new ArrayList<>();
    for (String query : safeQueries) {
      String normalized = normalize(query);
      if (normalized.isEmpty()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "查询关键词不能只包含标点或空白");
      }
      List<GenerationMatch> matches = nodes.stream()
          .filter(node -> scopedCodes.contains(node.getCode()))
          .filter(node -> parentCode == null || isDescendantOrSelf(node, parentCode, byCode))
          .filter(node -> !requireAssignable || node.isAssignable())
          .map(node -> match(node, aliases.getOrDefault(node.getCode(), List.of()), normalized))
          .filter(java.util.Objects::nonNull)
          .sorted(Comparator.comparingInt((QuestionTagSearchResponse.SearchMatch match) -> matchRank(match.matchedBy()))
              .thenComparing(match -> match.node().code()))
          .limit(limit)
          .map(match -> generationMatch(
              byCode.get(match.node().code()), match.matchedBy(), byCode, aliases))
          .toList();
      results.add(new GenerationQueryResult(query, matches));
    }
    return new GenerationQueryResponse(version, results);
  }

  private GenerationMatch generationMatch(
      QuestionKnowledgeNodeEntity node,
      String matchedBy,
      Map<String, QuestionKnowledgeNodeEntity> byCode,
      Map<String, List<String>> aliases
  ) {
    List<QuestionTagNodeResponse> path = new ArrayList<>();
    QuestionKnowledgeNodeEntity cursor = node;
    while (cursor != null) {
      path.add(response(cursor, aliases));
      cursor = cursor.getParentCode() == null ? null : byCode.get(cursor.getParentCode());
    }
    java.util.Collections.reverse(path);
    return new GenerationMatch(node.getCode(), node.getName(),
        path.stream().map(QuestionTagNodeResponse::name)
            .collect(java.util.stream.Collectors.joining(" / ")),
        node.getDescription(), node.isAssignable(), matchedBy);
  }

  private boolean isDescendantOrSelf(
      QuestionKnowledgeNodeEntity node, String ancestorCode,
      Map<String, QuestionKnowledgeNodeEntity> byCode
  ) {
    QuestionKnowledgeNodeEntity cursor = node;
    while (cursor != null) {
      if (ancestorCode.equals(cursor.getCode())) {
        return true;
      }
      cursor = cursor.getParentCode() == null ? null : byCode.get(cursor.getParentCode());
    }
    return false;
  }

  private boolean isWithinScope(String code, Set<String> roots) {
    return roots.stream().anyMatch(root -> root.equals("TECH_KNOWLEDGE")
        || code.equals(root) || code.startsWith(root + "."));
  }

  private void requireTaxonomyVersion(int version) {
    if (versionRepository.findById(version).isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "题目知识目录版本不存在: " + version);
    }
  }

  public record GenerationQueryResponse(int taxonomyVersion, List<GenerationQueryResult> results) {}
  public record GenerationQueryResult(String query, List<GenerationMatch> nodes) {}
  public record GenerationMatch(
      String code, String name, String path, String description, boolean assignable, String matchedBy
  ) {}

  public List<QuestionTagNodeResponse> roots(String skillId) {
    int version = currentVersionEntity().getVersion();
    Map<String, List<String>> aliases = aliasesByCode(version);
    List<QuestionKnowledgeNodeEntity> activeNodes = activeNodes(version);
    Map<String, QuestionKnowledgeNodeEntity> byCode = indexNodes(activeNodes);
    if (skillId == null || skillId.isBlank()) {
      return activeNodes.stream()
          .filter(node -> node.getParentCode() == null)
          .map(node -> response(node, aliases))
          .toList();
    }
    return scopeRoots(version, skillId).stream()
        .map(byCode::get)
        .filter(node -> node != null && node.getStatus() == Status.ACTIVE)
        .map(node -> response(node, aliases))
        .toList();
  }

  public List<QuestionTagNodeResponse> children(String code) {
    int version = currentVersionEntity().getVersion();
    requireNode(version, code);
    Map<String, List<String>> aliases = aliasesByCode(version);
    return nodeRepository.findByTaxonomyVersionAndParentCodeAndStatusOrderBySortOrderAscCodeAsc(
            version, code, Status.ACTIVE)
        .stream()
        .map(node -> response(node, aliases))
        .toList();
  }

  public QuestionTagNodeDetailResponse detail(String code) {
    int version = currentVersionEntity().getVersion();
    QuestionKnowledgeNodeEntity node = requireNode(version, code);
    Map<String, QuestionKnowledgeNodeEntity> nodes = indexNodes(activeNodes(version));
    Map<String, List<String>> aliases = aliasesByCode(version);

    List<QuestionTagNodeResponse> path = new ArrayList<>();
    QuestionKnowledgeNodeEntity cursor = node;
    while (cursor != null) {
      path.add(response(cursor, aliases));
      cursor = cursor.getParentCode() == null ? null : nodes.get(cursor.getParentCode());
    }
    java.util.Collections.reverse(path);

    List<QuestionTagNodeDetailResponse.RelationResponse> relations = relationRepository
        .findByIdTaxonomyVersion(version).stream()
        .filter(relation -> code.equals(relation.getId().getSourceCode())
            || code.equals(relation.getId().getTargetCode()))
        .map(relation -> new QuestionTagNodeDetailResponse.RelationResponse(
            relation.getId().getSourceCode(), relation.getId().getTargetCode(),
            relation.getId().getRelationType().name()))
        .toList();
    return new QuestionTagNodeDetailResponse(version, response(node, aliases), path, relations);
  }

  public QuestionTagSearchResponse search(
      String query, String skillId, Boolean assignableOnly, Integer requestedLimit
  ) {
    if (query == null || query.isBlank() || query.length() > 100) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "搜索关键词长度必须为 1～100 个字符");
    }
    String normalizedQuery = normalize(query);
    if (normalizedQuery.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "搜索关键词不能只包含标点或空白");
    }
    int limit = requestedLimit == null ? DEFAULT_SEARCH_LIMIT : requestedLimit;
    if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "搜索结果数量必须为 1～50");
    }

    int version = currentVersionEntity().getVersion();
    List<QuestionKnowledgeNodeEntity> nodes = activeNodes(version);
    Set<String> allowedCodes = skillId == null || skillId.isBlank()
        ? nodes.stream().map(QuestionKnowledgeNodeEntity::getCode).collect(java.util.stream.Collectors.toSet())
        : scopedCodes(nodes, scopeRoots(version, skillId));
    Map<String, List<String>> aliases = aliasesByCode(version);
    boolean requireAssignable = Boolean.TRUE.equals(assignableOnly);

    List<QuestionTagSearchResponse.SearchMatch> matches = nodes.stream()
        .filter(node -> allowedCodes.contains(node.getCode()))
        .filter(node -> !requireAssignable || node.isAssignable())
        .map(node -> match(node, aliases.getOrDefault(node.getCode(), List.of()), normalizedQuery))
        .filter(java.util.Objects::nonNull)
        .sorted(Comparator.comparingInt((QuestionTagSearchResponse.SearchMatch match) -> matchRank(match.matchedBy()))
            .thenComparing(match -> match.node().code()))
        .limit(limit)
        .toList();
    return new QuestionTagSearchResponse(version, query, matches);
  }

  private QuestionTagSearchResponse.SearchMatch match(
      QuestionKnowledgeNodeEntity node, List<String> aliases, String normalizedQuery
  ) {
    if (normalize(node.getCode()).contains(normalizedQuery)) {
      return new QuestionTagSearchResponse.SearchMatch(QuestionTagNodeResponse.from(node, aliases), "CODE");
    }
    if (normalize(node.getName()).contains(normalizedQuery)) {
      return new QuestionTagSearchResponse.SearchMatch(QuestionTagNodeResponse.from(node, aliases), "NAME");
    }
    if (aliases.stream().anyMatch(alias -> normalize(alias).contains(normalizedQuery))) {
      return new QuestionTagSearchResponse.SearchMatch(QuestionTagNodeResponse.from(node, aliases), "ALIAS");
    }
    if (node.getDescription() != null && normalize(node.getDescription()).contains(normalizedQuery)) {
      return new QuestionTagSearchResponse.SearchMatch(QuestionTagNodeResponse.from(node, aliases), "DESCRIPTION");
    }
    return null;
  }

  private int matchRank(String matchedBy) {
    return switch (matchedBy) {
      case "CODE" -> 0;
      case "NAME" -> 1;
      case "ALIAS" -> 2;
      default -> 3;
    };
  }

  private Set<String> scopedCodes(List<QuestionKnowledgeNodeEntity> nodes, List<String> roots) {
    Set<String> allowed = new HashSet<>();
    for (String root : roots) {
      for (QuestionKnowledgeNodeEntity node : nodes) {
        if ("TECH_KNOWLEDGE".equals(root)
            || node.getCode().equals(root) || node.getCode().startsWith(root + ".")) {
          allowed.add(node.getCode());
        }
      }
    }
    return allowed;
  }

  private List<String> scopeRoots(int version, String skillId) {
    List<QuestionSkillKnowledgeScopeEntity> scopes = scopeRepository
        .findByIdTaxonomyVersionAndIdSkillId(version, skillId);
    if (scopes.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "未找到题目知识目录 Skill scope: " + skillId);
    }
    return scopes.stream().map(scope -> scope.getId().getNodeCode()).toList();
  }

  private QuestionKnowledgeNodeEntity requireNode(int version, String code) {
    if (code == null || code.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识节点 code 不能为空");
    }
    return nodeRepository.findByTaxonomyVersionAndCode(version, code)
        .filter(node -> node.getStatus() == Status.ACTIVE)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "题目知识节点不存在: " + code));
  }

  private QuestionTaxonomyVersionEntity currentVersionEntity() {
    return versionRepository.findFirstByStatusOrderByVersionDesc(QuestionTaxonomyVersionEntity.Status.ACTIVE)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "题目知识目录尚未发布"));
  }

  private List<QuestionKnowledgeNodeEntity> activeNodes(int version) {
    return nodeRepository.findByTaxonomyVersionAndStatusOrderBySortOrderAscCodeAsc(version, Status.ACTIVE);
  }

  private Map<String, QuestionKnowledgeNodeEntity> indexNodes(List<QuestionKnowledgeNodeEntity> nodes) {
    Map<String, QuestionKnowledgeNodeEntity> index = new HashMap<>();
    nodes.forEach(node -> index.put(node.getCode(), node));
    return index;
  }

  private Map<String, List<String>> aliasesByCode(int version) {
    Map<String, List<String>> aliases = new HashMap<>();
    for (QuestionKnowledgeNodeAliasEntity alias : aliasRepository.findByTaxonomyVersion(version)) {
      aliases.computeIfAbsent(alias.getNodeCode(), ignored -> new ArrayList<>()).add(alias.getAlias());
    }
    return aliases;
  }

  private QuestionTagNodeResponse response(
      QuestionKnowledgeNodeEntity node, Map<String, List<String>> aliases
  ) {
    return QuestionTagNodeResponse.from(node, aliases.getOrDefault(node.getCode(), List.of()));
  }

  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s\\p{Punct}，。；：！？（）【】《》、]+", "");
  }
}
