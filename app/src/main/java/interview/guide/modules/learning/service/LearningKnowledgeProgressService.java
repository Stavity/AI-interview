package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.tenant.TenantContext;
import interview.guide.modules.learning.model.LearningAssessmentEvidenceEntity;
import interview.guide.modules.learning.model.LearningKnowledgeProgressEntity;
import interview.guide.modules.learning.repository.LearningAssessmentEvidenceRepository;
import interview.guide.modules.learning.repository.LearningKnowledgeProgressRepository;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.AssessmentEvidence;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.KnowledgeNode;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.NodeProgress;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.ProgressStatus;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.TagRole;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity.Status;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.repository.QuestionTaxonomyVersionRepository;
import interview.guide.modules.questiontag.service.QuestionTagQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningKnowledgeProgressService {

  private static final int MAX_EVIDENCE_QUERY_LIMIT = 200;

  private final LearningAssessmentEvidenceRepository evidenceRepository;
  private final LearningKnowledgeProgressRepository progressRepository;
  private final QuestionKnowledgeNodeRepository nodeRepository;
  private final QuestionTaxonomyVersionRepository versionRepository;
  private final QuestionTagQueryService taxonomyVersionResolver;
  private final LearningEvidenceAggregator aggregator;
  private final LearningProperties properties;

  @Transactional
  public ProgressSnapshot rebuildCurrentUser(Integer requestedTaxonomyVersion) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    int taxonomyVersion = resolveVersion(requestedTaxonomyVersion);
    rebuild(ownerUserId, taxonomyVersion);
    return snapshot(ownerUserId, taxonomyVersion);
  }

  @Transactional
  public void rebuild(long ownerUserId, int taxonomyVersion) {
    long currentOwner = TenantContext.requireCurrentTenantId();
    if (ownerUserId != currentOwner) {
      throw new IllegalStateException("学习进度只能为当前认证用户重算");
    }
    requireVersion(taxonomyVersion);
    List<QuestionKnowledgeNodeEntity> nodes = activeNodes(taxonomyVersion);
    List<KnowledgeNode> taxonomy = nodes.stream().map(this::toKnowledgeNode).toList();
    List<AssessmentEvidence> evidence = evidenceRepository
        .findByOwnerUserIdAndTaxonomyVersionOrderByAssessedAtDescIdDesc(ownerUserId, taxonomyVersion)
        .stream().map(this::toEvidence).toList();
    LocalDateTime aggregatedAt = LocalDateTime.now();
    List<NodeProgress> calculated = aggregator.aggregate(
        ownerUserId, taxonomyVersion, taxonomy, evidence, aggregatedAt);

    progressRepository.deleteByOwnerUserIdAndTaxonomyVersion(ownerUserId, taxonomyVersion);
    progressRepository.flush();
    progressRepository.saveAll(calculated.stream()
        .map(item -> LearningKnowledgeProgressEntity.from(
            ownerUserId, taxonomyVersion, properties.getAggregationRuleVersion(), item, aggregatedAt))
        .toList());
  }

  @Transactional(readOnly = true)
  public ProgressSnapshot currentProgress(Integer requestedTaxonomyVersion) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    return snapshot(ownerUserId, resolveVersion(requestedTaxonomyVersion));
  }

  @Transactional(readOnly = true)
  public List<EvidenceItem> evidence(Integer requestedTaxonomyVersion, String nodeCode, Integer requestedLimit) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    int taxonomyVersion = resolveVersion(requestedTaxonomyVersion);
    int limit = requestedLimit == null ? 50 : requestedLimit;
    if (limit < 1 || limit > MAX_EVIDENCE_QUERY_LIMIT) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "学习证据查询数量必须为 1～200");
    }
    var pageable = PageRequest.of(0, limit);
    List<LearningAssessmentEvidenceEntity> values = nodeCode == null || nodeCode.isBlank()
        ? evidenceRepository.findByOwnerUserIdAndTaxonomyVersionOrderByAssessedAtDescIdDesc(
            ownerUserId, taxonomyVersion, pageable)
        : evidenceRepository
            .findByOwnerUserIdAndTaxonomyVersionAndNodeCodeOrderByAssessedAtDescIdDesc(
                ownerUserId, taxonomyVersion, nodeCode.trim(), pageable);
    return values.stream().map(EvidenceItem::from).toList();
  }

  private ProgressSnapshot snapshot(long ownerUserId, int taxonomyVersion) {
    List<QuestionKnowledgeNodeEntity> nodes = activeNodes(taxonomyVersion);
    Map<String, LearningKnowledgeProgressEntity> byCode = progressRepository
        .findByOwnerUserIdAndTaxonomyVersionOrderByNodeCodeAsc(ownerUserId, taxonomyVersion)
        .stream().collect(Collectors.toMap(
            LearningKnowledgeProgressEntity::getNodeCode, Function.identity()));
    List<NodeSnapshot> values = nodes.stream()
        .map(node -> NodeSnapshot.from(node, byCode.get(node.getCode())))
        .toList();
    LocalDateTime aggregatedAt = byCode.values().stream()
        .map(LearningKnowledgeProgressEntity::getAggregatedAt)
        .max(LocalDateTime::compareTo).orElse(null);
    return new ProgressSnapshot(
        taxonomyVersion, properties.getAggregationRuleVersion(), aggregatedAt, values);
  }

  private int resolveVersion(Integer requestedTaxonomyVersion) {
    if (requestedTaxonomyVersion != null) {
      requireVersion(requestedTaxonomyVersion);
      return requestedTaxonomyVersion;
    }
    return taxonomyVersionResolver.currentVersionNumber();
  }

  private void requireVersion(int taxonomyVersion) {
    if (versionRepository.findById(taxonomyVersion).isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "题目知识目录版本不存在: " + taxonomyVersion);
    }
  }

  private List<QuestionKnowledgeNodeEntity> activeNodes(int taxonomyVersion) {
    return nodeRepository.findByTaxonomyVersionAndStatusOrderBySortOrderAscCodeAsc(
        taxonomyVersion, Status.ACTIVE);
  }

  private KnowledgeNode toKnowledgeNode(QuestionKnowledgeNodeEntity node) {
    return new KnowledgeNode(node.getCode(), node.getParentCode(), node.isAssignable(), true, node.getSortOrder());
  }

  private AssessmentEvidence toEvidence(LearningAssessmentEvidenceEntity entity) {
    return new AssessmentEvidence(
        entity.getOwnerUserId(), entity.getTaxonomyVersion(), entity.getSourceType(),
        entity.getSourceSessionId(), entity.getQuestionIndex(), entity.getNodeCode(),
        TagRole.valueOf(entity.getTagRole()), entity.getScore(), entity.isVerified(),
        entity.getAssessedAt(), entity.getEvaluationFingerprint(), entity.getId());
  }

  public record ProgressSnapshot(
      int taxonomyVersion,
      int aggregationRuleVersion,
      LocalDateTime aggregatedAt,
      List<NodeSnapshot> nodes
  ) {}

  public record NodeSnapshot(
      String code,
      String name,
      String parentCode,
      boolean assignable,
      int sortOrder,
      int masteryScore,
      int evidenceCount,
      int distinctSessionCount,
      int consecutiveCorrectCount,
      int consecutiveIncorrectCount,
      int confidenceScore,
      ProgressStatus status,
      LocalDateTime lastPracticedAt
  ) {
    static NodeSnapshot from(
        QuestionKnowledgeNodeEntity node, LearningKnowledgeProgressEntity progress
    ) {
      return new NodeSnapshot(
          node.getCode(), node.getName(), node.getParentCode(), node.isAssignable(), node.getSortOrder(),
          progress == null ? 0 : progress.getMasteryScore(),
          progress == null ? 0 : progress.getEvidenceCount(),
          progress == null ? 0 : progress.getDistinctSessionCount(),
          progress == null ? 0 : progress.getConsecutiveCorrectCount(),
          progress == null ? 0 : progress.getConsecutiveIncorrectCount(),
          progress == null ? 0 : progress.getConfidenceScore(),
          progress == null ? ProgressStatus.NOT_STARTED : ProgressStatus.valueOf(progress.getStatus()),
          progress == null ? null : progress.getLastPracticedAt());
    }
  }

  public record EvidenceItem(
      long id,
      String sourceType,
      String sourceSessionId,
      Long sourceAnswerId,
      Long sourceVoiceMessageId,
      long evaluationVersion,
      int questionIndex,
      int taxonomyVersion,
      String nodeCode,
      String tagRole,
      int score,
      LocalDateTime assessedAt
  ) {
    static EvidenceItem from(LearningAssessmentEvidenceEntity entity) {
      return new EvidenceItem(
          entity.getId(), entity.getSourceType(), entity.getSourceSessionId(),
          entity.getSourceAnswerId(), entity.getSourceVoiceMessageId(), entity.getEvaluationVersion(),
          entity.getQuestionIndex(), entity.getTaxonomyVersion(), entity.getNodeCode(),
          entity.getTagRole(), entity.getScore(), entity.getAssessedAt());
    }
  }
}
