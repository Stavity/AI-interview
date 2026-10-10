package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.tenant.TenantContext;
import interview.guide.modules.learning.model.LearningKnowledgeProgressEntity;
import interview.guide.modules.learning.model.LearningPathEntity;
import interview.guide.modules.learning.model.LearningPathItemEntity;
import interview.guide.modules.learning.model.LearningPathItemResourceEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningKnowledgeProgressRepository;
import interview.guide.modules.learning.repository.LearningPathItemRepository;
import interview.guide.modules.learning.repository.LearningPathItemResourceRepository;
import interview.guide.modules.learning.repository.LearningPathRepository;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity.RelationType;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity.Status;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRelationRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.service.QuestionTagQueryService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists explainable, deterministic learning paths from the current user's knowledge progress. */
@Service
@RequiredArgsConstructor
public class PersonalizedLearningPathService {

  private static final int ROUTE_LIMIT = 8;

  private final LearningPathRepository pathRepository;
  private final LearningPathItemRepository itemRepository;
  private final LearningPathItemResourceRepository itemResourceRepository;
  private final LearningSkillSourceRepository skillSourceRepository;
  private final LearningKnowledgeProgressRepository progressRepository;
  private final QuestionKnowledgeNodeRepository nodeRepository;
  private final QuestionKnowledgeNodeRelationRepository relationRepository;
  private final QuestionTagQueryService taxonomyVersionResolver;
  private final LearningRoutePlanner routePlanner;
  private final LearningPathResourceMatcher resourceMatcher;
  private final LearningPathNarrativeComposer narrativeComposer;
  private final LearningProperties properties;

  @Transactional(readOnly = true)
  public PathSnapshot current() {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    return pathRepository.findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, LearningPathEntity.Status.ACTIVE)
        .map(path -> snapshot(path, ownerUserId))
        .orElseGet(() -> PathSnapshot.empty(
            taxonomyVersionResolver.currentVersionNumber(), properties.getRouteRuleVersion()));
  }

  @Transactional
  public PathSnapshot regenerate(String requestedGoal) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    LearningPathEntity previous = pathRepository
        .findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, LearningPathEntity.Status.ACTIVE)
        .orElse(null);
    String goal = normalizeGoal(requestedGoal, previous == null ? null : previous.getGoal());
    return regenerate(ownerUserId, taxonomyVersionResolver.currentVersionNumber(), goal, "MANUAL_REFRESH");
  }

  /** Called after a successful evidence write, in the same transaction as the progress rebuild. */
  @Transactional
  public void refreshAfterKnowledgeProgressChanged(long ownerUserId, int taxonomyVersion) {
    if (TenantContext.requireCurrentTenantId() != ownerUserId) {
      throw new IllegalStateException("学习路线只能为当前认证用户更新");
    }
    LearningPathEntity previous = pathRepository
        .findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, LearningPathEntity.Status.ACTIVE)
        .orElse(null);
    regenerate(ownerUserId, taxonomyVersion, previous == null ? null : previous.getGoal(), "INTERVIEW_EVALUATED");
  }

  @Transactional
  public PathSnapshot completeItem(long itemId) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    LearningPathItemEntity item = itemRepository.findByIdAndOwnerUserId(itemId, ownerUserId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "学习路线任务不存在: " + itemId));
    item.complete();
    return pathRepository.findById(item.getPathId())
        .filter(path -> path.getOwnerUserId() == ownerUserId && path.getStatus() == LearningPathEntity.Status.ACTIVE)
        .map(path -> snapshot(path, ownerUserId))
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "当前学习路线已更新，请刷新后继续"));
  }

  private PathSnapshot regenerate(long ownerUserId, int taxonomyVersion, String goal, String triggerReason) {
    List<QuestionKnowledgeNodeEntity> nodes = nodeRepository
        .findByTaxonomyVersionAndStatusOrderBySortOrderAscCodeAsc(taxonomyVersion, Status.ACTIVE);
    Map<String, QuestionKnowledgeNodeEntity> nodesByCode = new HashMap<>();
    nodes.forEach(node -> nodesByCode.put(node.getCode(), node));
    List<LearningKnowledgeProgressEntity> progressEntities = progressRepository
        .findByOwnerUserIdAndTaxonomyVersionOrderByNodeCodeAsc(ownerUserId, taxonomyVersion);
    List<LearningEvidenceAggregator.NodeProgress> progress = progressEntities.stream()
        .map(this::toProgress).toList();
    List<LearningRoutePlanner.Prerequisite> prerequisites = relationRepository
        .findByIdTaxonomyVersion(taxonomyVersion).stream()
        .filter(relation -> relation.getId().getRelationType() == RelationType.PREREQUISITE_OF)
        .map(relation -> new LearningRoutePlanner.Prerequisite(
            relation.getId().getSourceCode(), relation.getId().getTargetCode()))
        .toList();
    List<LearningEvidenceAggregator.KnowledgeNode> taxonomy = nodes.stream()
        .map(node -> new LearningEvidenceAggregator.KnowledgeNode(
            node.getCode(), node.getParentCode(), node.isAssignable(), true, node.getSortOrder()))
        .toList();
    List<LearningRoutePlanner.RouteStep> planned = routePlanner.plan(
        taxonomy, progress, prerequisites, ROUTE_LIMIT);
    boolean coldStart = planned.isEmpty() && progressEntities.isEmpty();
    if (coldStart) {
      planned = coldStartSteps(nodes);
    }

    LearningPathEntity previous = pathRepository
        .findFirstByOwnerUserIdAndStatusOrderByIdDesc(ownerUserId, LearningPathEntity.Status.ACTIVE)
        .orElse(null);
    if (previous != null) {
      previous.supersede();
      pathRepository.save(previous);
      pathRepository.flush();
    }
    String ruleSummary = summaryFor(planned, coldStart);
    LearningPathNarrativeComposer.Composition composition = narrativeComposer.compose(
        goal, planned, nodesByCode, ruleSummary);
    LearningPathEntity path = pathRepository.save(LearningPathEntity.active(
        ownerUserId, taxonomyVersion, properties.getRouteRuleVersion(), goal, triggerReason,
        composition.summary(), composition.source()));

    Map<String, LearningKnowledgeProgressEntity> progressByNode = new HashMap<>();
    progressEntities.forEach(value -> progressByNode.put(value.getNodeCode(), value));
    List<LearningPathItemEntity> items = new java.util.ArrayList<>();
    int sequence = 1;
    for (LearningRoutePlanner.RouteStep step : planned) {
      QuestionKnowledgeNodeEntity node = nodesByCode.get(step.nodeCode());
      if (node == null) {
        continue;
      }
      LearningKnowledgeProgressEntity nodeProgress = progressByNode.get(step.nodeCode());
      boolean prerequisite = step.prerequisite();
      items.add(LearningPathItemEntity.pending(
          ownerUserId, path.getId(), taxonomyVersion, sequence++, step.nodeCode(), node.getName(),
          step.masteryScore(), nodeProgress == null ? 0 : nodeProgress.getEvidenceCount(),
          difficultyFor(step.masteryScore(), prerequisite, coldStart), estimatedMinutes(prerequisite, coldStart),
          prerequisite, coldStart
              ? "你还没有足够的面试证据，先从该目录的基础知识点开始建立学习基线。"
              : step.reason()));
    }
    List<LearningPathItemEntity> savedItems = itemRepository.saveAll(items);
    List<LearningSkillSourceEntity> activeSources = skillSourceRepository
        .findByStatusOrderByIdAsc(LearningSkillSourceEntity.Status.ACTIVE);
    List<LearningPathItemResourceEntity> resources = new java.util.ArrayList<>();
    for (LearningPathItemEntity item : savedItems) {
      QuestionKnowledgeNodeEntity node = nodesByCode.get(item.getNodeCode());
      if (node == null) {
        continue;
      }
      for (LearningPathResourceMatcher.Match match : resourceMatcher.match(node, activeSources)) {
        resources.add(LearningPathItemResourceEntity.matched(ownerUserId, item.getId(), match.source(),
            match.score(), match.reason()));
      }
    }
    itemResourceRepository.saveAll(resources);
    return snapshot(path, ownerUserId);
  }

  private List<LearningRoutePlanner.RouteStep> coldStartSteps(List<QuestionKnowledgeNodeEntity> nodes) {
    return nodes.stream()
        .filter(QuestionKnowledgeNodeEntity::isAssignable)
        .sorted(Comparator.comparingInt(QuestionKnowledgeNodeEntity::getSortOrder)
            .thenComparing(QuestionKnowledgeNodeEntity::getCode))
        .limit(3)
        .map(node -> new LearningRoutePlanner.RouteStep(
            node.getCode(), node.getParentCode(), 0, 0, false,
            "你还没有足够的面试证据，先从该目录的基础知识点开始建立学习基线。"))
        .toList();
  }

  private LearningEvidenceAggregator.NodeProgress toProgress(LearningKnowledgeProgressEntity value) {
    return new LearningEvidenceAggregator.NodeProgress(
        value.getNodeCode(), null, value.getMasteryScore(), value.getEvidenceCount(),
        value.getDistinctSessionCount(), value.getConsecutiveCorrectCount(),
        value.getConsecutiveIncorrectCount(), value.getConfidenceScore(),
        LearningEvidenceAggregator.ProgressStatus.valueOf(value.getStatus()), value.getLastPracticedAt());
  }

  private PathSnapshot snapshot(LearningPathEntity path, long ownerUserId) {
    List<LearningPathItemEntity> pathItems = itemRepository
        .findByPathIdAndOwnerUserIdOrderBySequenceNoAsc(path.getId(), ownerUserId);
    Map<Long, List<PathResourceSnapshot>> resourcesByItem = new HashMap<>();
    if (!pathItems.isEmpty()) {
      itemResourceRepository.findByPathItemIdInAndOwnerUserIdOrderByMatchScoreDescIdAsc(
          pathItems.stream().map(LearningPathItemEntity::getId).toList(), ownerUserId)
          .forEach(resource -> resourcesByItem.computeIfAbsent(resource.getPathItemId(), ignored ->
              new java.util.ArrayList<>()).add(PathResourceSnapshot.from(resource)));
    }
    List<PathItemSnapshot> items = pathItems.stream()
        .map(item -> PathItemSnapshot.from(item, resourcesByItem.getOrDefault(item.getId(), List.of())))
        .toList();
    return PathSnapshot.from(path, items);
  }

  private static String normalizeGoal(String requestedGoal, String fallback) {
    if (requestedGoal == null || requestedGoal.isBlank()) {
      return fallback;
    }
    return requestedGoal.trim();
  }

  private static String summaryFor(List<LearningRoutePlanner.RouteStep> route, boolean coldStart) {
    if (route.isEmpty()) {
      return "当前证据表明你已掌握可推荐的知识点；完成新的面试后会自动重新评估路线。";
    }
    if (coldStart) {
      return "这是基于知识目录生成的起步路线；完成带标签的面试后会自动替换为个性化路线。";
    }
    return "路线优先补齐薄弱知识点及其尚未掌握的前置内容；每项建议均可追溯到当前掌握度。";
  }

  private static String difficultyFor(int masteryScore, boolean prerequisite, boolean coldStart) {
    if (coldStart || prerequisite || masteryScore < 40) {
      return "FOUNDATION";
    }
    return masteryScore < 70 ? "REINFORCEMENT" : "PRACTICE";
  }

  private static int estimatedMinutes(boolean prerequisite, boolean coldStart) {
    return prerequisite || coldStart ? 45 : 30;
  }

  public record PathSnapshot(
      Long id,
      int taxonomyVersion,
      int routeRuleVersion,
      String goal,
      String triggerReason,
      String compositionSource,
      String summary,
      String status,
      java.time.LocalDateTime createdAt,
      List<PathItemSnapshot> items
  ) {
    static PathSnapshot empty(int taxonomyVersion, int routeRuleVersion) {
      return new PathSnapshot(null, taxonomyVersion, routeRuleVersion, null, null,
          "RULE", "尚未生成学习路线。点击“生成路线”后，系统会根据当前知识点掌握度创建路线。",
          "EMPTY", null, List.of());
    }

    static PathSnapshot from(LearningPathEntity path, List<PathItemSnapshot> items) {
      return new PathSnapshot(path.getId(), path.getTaxonomyVersion(), path.getRouteRuleVersion(),
          path.getGoal(), path.getTriggerReason(), path.getCompositionSource(), path.getSummary(),
          path.getStatus().name(), path.getCreatedAt(), items);
    }
  }

  public record PathItemSnapshot(
      long id,
      int sequenceNo,
      String nodeCode,
      String nodeName,
      int masteryScore,
      int evidenceCount,
      String difficulty,
      int estimatedMinutes,
      boolean prerequisite,
      String recommendationReason,
      List<PathResourceSnapshot> resources,
      String status,
      java.time.LocalDateTime completedAt
  ) {
    static PathItemSnapshot from(LearningPathItemEntity item, List<PathResourceSnapshot> resources) {
      return new PathItemSnapshot(item.getId(), item.getSequenceNo(), item.getNodeCode(),
          item.getNodeName(), item.getMasteryScore(), item.getEvidenceCount(), item.getDifficulty(),
          item.getEstimatedMinutes(), item.isPrerequisite(), item.getRecommendationReason(),
          resources, item.getStatus().name(), item.getCompletedAt());
    }
  }

  public record PathResourceSnapshot(
      long sourceId, String displayName, String categoryKey, int matchScore, String matchReason
  ) {
    static PathResourceSnapshot from(LearningPathItemResourceEntity resource) {
      return new PathResourceSnapshot(resource.getSkillSourceId(), resource.getSourceDisplayName(),
          resource.getCategoryKey(), resource.getMatchScore(), resource.getMatchReason());
    }
  }
}
