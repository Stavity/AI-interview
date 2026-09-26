package interview.guide.modules.questiontag.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeAliasEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity.RelationType;
import interview.guide.modules.questiontag.model.QuestionSkillKnowledgeScopeEntity;
import interview.guide.modules.questiontag.model.QuestionSkillKnowledgeScopeEntity.Priority;
import interview.guide.modules.questiontag.model.QuestionSkillScopeDefinition;
import interview.guide.modules.questiontag.model.QuestionTagCatalogDefinition;
import interview.guide.modules.questiontag.model.QuestionTagCatalogDefinition.NodeDefinition;
import interview.guide.modules.questiontag.model.QuestionTaxonomyVersionEntity;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeAliasRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRelationRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.repository.QuestionSkillKnowledgeScopeRepository;
import interview.guide.modules.questiontag.repository.QuestionTaxonomyVersionRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuestionTagCatalogSyncService {

  private static final String CATALOG_RESOURCE = "classpath:question-tags/tag-catalog.yml";
  private static final String SCOPE_RESOURCE = "classpath:question-tags/skill-scopes.yml";

  private final ResourceLoader resourceLoader;
  private final QuestionTaxonomyVersionRepository versionRepository;
  private final QuestionKnowledgeNodeRepository nodeRepository;
  private final QuestionKnowledgeNodeAliasRepository aliasRepository;
  private final QuestionKnowledgeNodeRelationRepository relationRepository;
  private final QuestionSkillKnowledgeScopeRepository scopeRepository;

  @EventListener(ApplicationReadyEvent.class)
  public void syncCatalogOnApplicationReady() {
    syncCatalog();
  }

  @Transactional
  public void syncCatalog() {
    String catalogText = readResource(CATALOG_RESOURCE);
    String scopeText = readResource(SCOPE_RESOURCE);
    Yaml yaml = new Yaml();
    QuestionTagCatalogDefinition catalog = yaml.loadAs(catalogText, QuestionTagCatalogDefinition.class);
    QuestionSkillScopeDefinition scopes = yaml.loadAs(scopeText, QuestionSkillScopeDefinition.class);
    validate(catalog, scopes);

    int version = catalog.getVersion();
    String contentHash = sha256(catalogText + "\n--skill-scopes--\n" + scopeText);
    QuestionTaxonomyVersionEntity existing = versionRepository.findById(version).orElse(null);
    if (existing != null) {
      if (!existing.getContentHash().equals(contentHash)) {
        throw new IllegalStateException("题目知识目录版本 " + version
            + " 已发布且内容发生变化，请递增 tag-catalog.yml 与 skill-scopes.yml 的 version");
      }
      log.info("题目知识目录已存在: version={}, hash={}", version, contentHash);
      return;
    }

    QuestionTaxonomyVersionEntity active = versionRepository
        .findFirstByStatusOrderByVersionDesc(QuestionTaxonomyVersionEntity.Status.ACTIVE)
        .orElse(null);
    if (active != null && active.getVersion() >= version) {
      throw new IllegalStateException("题目知识目录版本必须递增: active=" + active.getVersion()
          + ", configured=" + version);
    }
    if (active != null) {
      active.setStatus(QuestionTaxonomyVersionEntity.Status.ARCHIVED);
      versionRepository.saveAndFlush(active);
    }

    versionRepository.saveAndFlush(QuestionTaxonomyVersionEntity.active(version, contentHash));
    Map<String, NodeDefinition> definitions = new LinkedHashMap<>();
    List<NodeWithParent> flattened = new ArrayList<>();
    int[] order = {0};
    for (NodeDefinition root : catalog.getNodes()) {
      flatten(root, null, flattened, definitions, order);
    }
    for (NodeWithParent item : flattened) {
      NodeDefinition node = item.node();
      QuestionKnowledgeNodeEntity entity = nodeRepository.save(QuestionKnowledgeNodeEntity.create(
          version,
          node.getCode(),
          node.getName().trim(),
          item.parentCode(),
          node.getNodeKind(),
          trimToNull(node.getDescription()),
          Boolean.TRUE.equals(node.getAssignable()),
          item.sortOrder()
      ));
      List<QuestionKnowledgeNodeAliasEntity> aliases = normalizeAliases(node.getAliases()).stream()
          .map(alias -> QuestionKnowledgeNodeAliasEntity.create(version, entity.getCode(), alias,
              normalize(alias)))
          .toList();
      if (!aliases.isEmpty()) {
        aliasRepository.saveAll(aliases);
      }
    }

    List<QuestionKnowledgeNodeRelationEntity> relations = catalog.getRelations().stream()
        .map(relation -> QuestionKnowledgeNodeRelationEntity.create(
            version,
            relation.getSourceCode(),
            relation.getTargetCode(),
            RelationType.valueOf(relation.getRelationType())
        ))
        .toList();
    if (!relations.isEmpty()) {
      relationRepository.saveAll(relations);
    }

    List<QuestionSkillKnowledgeScopeEntity> skillScopes = scopes.getScopes().stream()
        .flatMap(scope -> scope.getRoots().stream().map(rootCode -> QuestionSkillKnowledgeScopeEntity.create(
            version,
            scope.getSkillId(),
            rootCode,
            true,
            Priority.NORMAL,
            java.math.BigDecimal.ONE
        )))
        .toList();
    scopeRepository.saveAll(skillScopes);

    log.info("题目知识目录同步完成: version={}, nodes={}, aliases={}, relations={}, skillScopes={}",
        version, flattened.size(), flattened.stream().map(item -> item.node().getAliases().size()).mapToInt(Integer::intValue).sum(),
        relations.size(), skillScopes.size());
  }

  private void validate(QuestionTagCatalogDefinition catalog, QuestionSkillScopeDefinition scopes) {
    if (catalog == null || catalog.getVersion() == null || catalog.getVersion() < 1
        || catalog.getNodes() == null || catalog.getNodes().isEmpty()) {
      throw new IllegalStateException("题目知识目录缺少有效版本或根节点");
    }
    if (scopes == null || !catalog.getVersion().equals(scopes.getVersion()) || scopes.getScopes() == null) {
      throw new IllegalStateException("题目知识目录与 Skill scope 必须存在且版本一致");
    }

    Map<String, NodeDefinition> definitions = new LinkedHashMap<>();
    List<NodeWithParent> flattened = new ArrayList<>();
    int[] order = {0};
    for (NodeDefinition root : catalog.getNodes()) {
      flatten(root, null, flattened, definitions, order);
    }
    if (definitions.isEmpty()) {
      throw new IllegalStateException("题目知识目录没有节点");
    }

    Set<String> scopeKeys = new HashSet<>();
    for (QuestionSkillScopeDefinition.ScopeDefinition scope : scopes.getScopes()) {
      if (scope.getSkillId() == null || scope.getSkillId().isBlank() || scope.getRoots() == null
          || scope.getRoots().isEmpty()) {
        throw new IllegalStateException("每个 Skill scope 都必须配置 skillId 和至少一个 root");
      }
      if (!scopeKeys.add(scope.getSkillId())) {
        throw new IllegalStateException("Skill scope 重复: " + scope.getSkillId());
      }
      for (String root : scope.getRoots()) {
        if (!definitions.containsKey(root)) {
          throw new IllegalStateException("Skill scope 引用了不存在的知识节点: " + scope.getSkillId() + " -> " + root);
        }
      }
    }

    Set<String> relations = new HashSet<>();
    for (QuestionTagCatalogDefinition.RelationDefinition relation : catalog.getRelations()) {
      if (relation.getSourceCode() == null || relation.getTargetCode() == null
          || !definitions.containsKey(relation.getSourceCode()) || !definitions.containsKey(relation.getTargetCode())) {
        throw new IllegalStateException("知识节点关系引用了不存在的节点");
      }
      if (relation.getSourceCode().equals(relation.getTargetCode())) {
        throw new IllegalStateException("知识节点不能与自身建立关系: " + relation.getSourceCode());
      }
      RelationType.valueOf(relation.getRelationType());
      String relationKey = relation.getSourceCode() + "\u0000" + relation.getTargetCode()
          + "\u0000" + relation.getRelationType();
      if (!relations.add(relationKey)) {
        throw new IllegalStateException("知识节点关系重复: " + relationKey);
      }
    }

    Map<String, String> normalizedAliases = new LinkedHashMap<>();
    for (Map.Entry<String, NodeDefinition> entry : definitions.entrySet()) {
      for (String alias : normalizeAliases(entry.getValue().getAliases())) {
        validateAliasUniqueness(normalizedAliases, normalize(alias), entry.getKey());
      }
    }
  }

  private void flatten(
      NodeDefinition node,
      String parentCode,
      List<NodeWithParent> flattened,
      Map<String, NodeDefinition> definitions,
      int[] order
  ) {
    if (node == null || node.getCode() == null || !node.getCode().matches("[A-Z0-9_]+(?:\\.[A-Z0-9_]+)*")
        || node.getName() == null || node.getName().isBlank()) {
      throw new IllegalStateException("题目知识节点必须有合法 code 与 name");
    }
    // TECH_KNOWLEDGE is a non-assignable catalog wrapper; stable codes start at its children.
    if (parentCode != null && !parentCode.equals("TECH_KNOWLEDGE")
        && !node.getCode().startsWith(parentCode + ".")) {
      throw new IllegalStateException("子节点 code 必须以前序节点 code 开头: " + parentCode + " -> " + node.getCode());
    }
    if (definitions.putIfAbsent(node.getCode(), node) != null) {
      throw new IllegalStateException("题目知识节点 code 重复: " + node.getCode());
    }
    flattened.add(new NodeWithParent(node, parentCode, order[0]++));
    if (node.getChildren() != null) {
      for (NodeDefinition child : node.getChildren()) {
        flatten(child, node.getCode(), flattened, definitions, order);
      }
    }
  }

  private List<String> normalizeAliases(List<String> aliases) {
    if (aliases == null) {
      return List.of();
    }
    return aliases.stream()
        .filter(alias -> alias != null && !alias.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  private String readResource(String location) {
    Resource resource = resourceLoader.getResource(location);
    if (!resource.exists()) {
      throw new IllegalStateException("缺少题目知识目录资源: " + location);
    }
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取题目知识目录失败: " + location, e);
    }
  }

  private String sha256(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 不支持 SHA-256", e);
    }
  }

  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s\\p{Punct}，。；：！？（）【】《》、]+", "");
  }

  private void validateAliasUniqueness(Map<String, String> aliases, String normalized, String code) {
    String existingCode = aliases.putIfAbsent(normalized, code);
    if (existingCode != null && !existingCode.equals(code)) {
      throw new IllegalStateException("题目知识目录名称或别名冲突: " + normalized
          + " -> " + existingCode + ", " + code);
    }
  }

  private String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record NodeWithParent(NodeDefinition node, String parentCode, int sortOrder) {}
}
