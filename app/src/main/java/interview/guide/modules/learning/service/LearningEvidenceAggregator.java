package interview.guide.modules.learning.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure learning-domain calculation. Persistence and authenticated owner resolution stay outside. */
public class LearningEvidenceAggregator {

  private final Policy policy;

  public LearningEvidenceAggregator() {
    this(Policy.defaults());
  }

  public LearningEvidenceAggregator(Policy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
    if (policy.recentSessionLimit() < 1 || policy.halfLifeDays() <= 0
        || policy.masteredScore() < 0 || policy.masteredScore() > 100
        || policy.masteredMinimumSessions() < 1) {
      throw new IllegalArgumentException("学习证据聚合配置超出有效范围");
    }
  }

  public List<NodeProgress> aggregate(
      long ownerUserId,
      int taxonomyVersion,
      Collection<KnowledgeNode> taxonomy,
      Collection<AssessmentEvidence> evidence,
      LocalDateTime now
  ) {
    Objects.requireNonNull(taxonomy, "taxonomy");
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(now, "now");

    Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
    taxonomy.stream().filter(KnowledgeNode::active)
        .sorted(Comparator.comparingInt(KnowledgeNode::sortOrder).thenComparing(KnowledgeNode::code))
        .forEach(node -> nodes.put(node.code(), node));

    Map<QuestionNodeKey, AssessmentEvidence> latestByQuestionAndNode = new HashMap<>();
    for (AssessmentEvidence item : evidence) {
      if (!isApplicable(item, ownerUserId, taxonomyVersion, nodes)) {
        continue;
      }
      QuestionNodeKey key = new QuestionNodeKey(
          item.sourceType(), item.sessionId(), item.questionIndex(), item.nodeCode());
      latestByQuestionAndNode.merge(key, item, LearningEvidenceAggregator::laterEvidence);
    }

    Map<QuestionNodeKey, AssessmentEvidence> byQuestionAndAncestor = new HashMap<>();
    for (AssessmentEvidence item : latestByQuestionAndNode.values()) {
      Set<String> visited = new HashSet<>();
      KnowledgeNode cursor = nodes.get(item.nodeCode());
      while (cursor != null && visited.add(cursor.code())) {
        QuestionNodeKey key = new QuestionNodeKey(
            item.sourceType(), item.sessionId(), item.questionIndex(), cursor.code());
        byQuestionAndAncestor.merge(key, item, LearningEvidenceAggregator::strongerObservation);
        cursor = cursor.parentCode() == null ? null : nodes.get(cursor.parentCode());
      }
    }

    Map<String, Map<SessionKey, List<AssessmentEvidence>>> byNodeAndSession = new HashMap<>();
    byQuestionAndAncestor.forEach((key, item) -> byNodeAndSession
        .computeIfAbsent(key.nodeCode(), ignored -> new HashMap<>())
        .computeIfAbsent(new SessionKey(key.sourceType(), key.sessionId()), ignored -> new ArrayList<>())
        .add(item));

    List<NodeProgress> result = new ArrayList<>();
    for (KnowledgeNode node : nodes.values()) {
      List<SessionObservation> sessions = byNodeAndSession.getOrDefault(node.code(), Map.of()).entrySet().stream()
          .map(entry -> sessionObservation(entry.getKey(), entry.getValue()))
          .sorted(Comparator.comparing(SessionObservation::assessedAt).reversed()
              .thenComparing(SessionObservation::sourceType)
              .thenComparing(SessionObservation::sessionId))
          .limit(policy.recentSessionLimit())
          .toList();
      result.add(toProgress(node, sessions, now));
    }
    return List.copyOf(result);
  }

  private static boolean isApplicable(
      AssessmentEvidence item, long ownerUserId, int taxonomyVersion, Map<String, KnowledgeNode> nodes
  ) {
    return item != null
        && item.ownerUserId() == ownerUserId
        && item.taxonomyVersion() == taxonomyVersion
        && item.sourceType() != null && !item.sourceType().isBlank()
        && item.sessionId() != null && !item.sessionId().isBlank()
        && item.questionIndex() >= 0
        && item.score() >= 0 && item.score() <= 100
        && item.role() != null
        && item.verified()
        && item.assessedAt() != null
        && item.fingerprint() != null && !item.fingerprint().isBlank()
        && nodes.containsKey(item.nodeCode())
        && nodes.get(item.nodeCode()).assignable();
  }

  private static AssessmentEvidence laterEvidence(AssessmentEvidence left, AssessmentEvidence right) {
    int byTime = left.assessedAt().compareTo(right.assessedAt());
    if (byTime != 0) {
      return byTime > 0 ? left : right;
    }
    if (left.eventSequence() != right.eventSequence()) {
      return left.eventSequence() > right.eventSequence() ? left : right;
    }
    return left.fingerprint().compareTo(right.fingerprint()) >= 0 ? left : right;
  }

  private static AssessmentEvidence strongerObservation(AssessmentEvidence left, AssessmentEvidence right) {
    if (left.role().weight() != right.role().weight()) {
      return left.role().weight() > right.role().weight() ? left : right;
    }
    return laterEvidence(left, right);
  }

  private static SessionObservation sessionObservation(SessionKey session, List<AssessmentEvidence> values) {
    double weightedScore = 0.0;
    double roleWeightSum = 0.0;
    LocalDateTime latest = null;
    for (AssessmentEvidence value : values) {
      double weight = value.role().weight();
      weightedScore += value.score() * weight;
      roleWeightSum += weight;
      if (latest == null || value.assessedAt().isAfter(latest)) {
        latest = value.assessedAt();
      }
    }
    return new SessionObservation(session.sourceType(), session.sessionId(), weightedScore / roleWeightSum,
        roleWeightSum / values.size(), values.size(), latest);
  }

  private NodeProgress toProgress(
      KnowledgeNode node, List<SessionObservation> sessions, LocalDateTime now
  ) {
    if (sessions.isEmpty()) {
      return new NodeProgress(node.code(), node.parentCode(), 0, 0, 0,
          ProgressStatus.NOT_STARTED, null);
    }

    double weightedScore = 0.0;
    double totalWeight = 0.0;
    int evidenceCount = 0;
    LocalDateTime lastPracticedAt = null;
    for (SessionObservation session : sessions) {
      long ageSeconds = Math.max(0L, Duration.between(session.assessedAt(), now).getSeconds());
      double ageDays = ageSeconds / 86_400.0;
      double decayWeight = Math.pow(0.5, ageDays / policy.halfLifeDays());
      double effectiveWeight = session.roleWeight() * decayWeight;
      weightedScore += session.score() * effectiveWeight;
      totalWeight += effectiveWeight;
      evidenceCount += session.evidenceCount();
      if (lastPracticedAt == null || session.assessedAt().isAfter(lastPracticedAt)) {
        lastPracticedAt = session.assessedAt();
      }
    }

    int masteryScore = (int) Math.round(weightedScore / totalWeight);
    int sessionCount = sessions.size();
    ProgressStatus status = masteryScore >= policy.masteredScore()
        && sessionCount >= policy.masteredMinimumSessions()
        ? ProgressStatus.MASTERED : ProgressStatus.LEARNING;
    return new NodeProgress(node.code(), node.parentCode(), masteryScore, evidenceCount, sessionCount,
        status, lastPracticedAt);
  }

  public enum TagRole {
    PRIMARY(1.0),
    SECONDARY(0.5);

    private final double weight;

    TagRole(double weight) {
      this.weight = weight;
    }

    public double weight() {
      return weight;
    }
  }

  public record Policy(
      int recentSessionLimit,
      double halfLifeDays,
      int masteredScore,
      int masteredMinimumSessions
  ) {
    public static Policy defaults() {
      return new Policy(20, 90.0, 85, 3);
    }
  }

  public enum ProgressStatus {
    NOT_STARTED,
    LEARNING,
    MASTERED
  }

  public record KnowledgeNode(
      String code, String parentCode, boolean assignable, boolean active, int sortOrder
  ) {}

  public record AssessmentEvidence(
      long ownerUserId,
      int taxonomyVersion,
      String sourceType,
      String sessionId,
      int questionIndex,
      String nodeCode,
      TagRole role,
      int score,
      boolean verified,
      LocalDateTime assessedAt,
      String fingerprint,
      long eventSequence
  ) {}

  public record NodeProgress(
      String nodeCode,
      String parentCode,
      int masteryScore,
      int evidenceCount,
      int distinctSessionCount,
      ProgressStatus status,
      LocalDateTime lastPracticedAt
  ) {}

  private record QuestionNodeKey(String sourceType, String sessionId, int questionIndex, String nodeCode) {}

  private record SessionKey(String sourceType, String sessionId) {}

  private record SessionObservation(
      String sourceType,
      String sessionId,
      double score,
      double roleWeight,
      int evidenceCount,
      LocalDateTime assessedAt
  ) {}
}
