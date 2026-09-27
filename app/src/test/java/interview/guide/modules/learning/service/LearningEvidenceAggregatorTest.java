package interview.guide.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.learning.service.LearningEvidenceAggregator.AssessmentEvidence;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.KnowledgeNode;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.NodeProgress;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.ProgressStatus;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.TagRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LearningEvidenceAggregatorTest {

  private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
  private final LearningEvidenceAggregator aggregator = new LearningEvidenceAggregator();
  private final LocalDateTime now = LocalDateTime.of(2026, 9, 27, 12, 0);

  @Test
  @DisplayName("只聚合当前用户、当前目录版本、已验证且有效的证据，并向父节点汇总")
  void shouldIsolateEvidenceAndRollUpToParents() {
    List<KnowledgeNode> taxonomy = List.of(
        node("ROOT", null, false, 0),
        node("ROOT.DB", "ROOT", false, 1),
        node("ROOT.DB.INDEX", "ROOT.DB", true, 2));
    List<AssessmentEvidence> evidence = List.of(
        evidence(7, 3, "s1", 0, "ROOT.DB.INDEX", TagRole.PRIMARY, 72, true, now, "a"),
        evidence(8, 3, "s2", 0, "ROOT.DB.INDEX", TagRole.PRIMARY, 100, true, now, "b"),
        evidence(7, 2, "s3", 0, "ROOT.DB.INDEX", TagRole.PRIMARY, 100, true, now, "c"),
        evidence(7, 3, "s4", 0, "ROOT.DB.INDEX", TagRole.PRIMARY, 100, false, now, "d"),
        evidence(7, 3, "s5", 0, "ROOT.DB.INDEX", TagRole.PRIMARY, 100, true, now, "e")
    );

    List<NodeProgress> result = aggregator.aggregate(7, 3, taxonomy, evidence, now);

    assertThat(result).extracting(NodeProgress::nodeCode)
        .containsExactly("ROOT", "ROOT.DB", "ROOT.DB.INDEX");
    assertThat(result).allSatisfy(progress -> {
      assertThat(progress.masteryScore()).isEqualTo(86);
      assertThat(progress.evidenceCount()).isEqualTo(2);
      assertThat(progress.distinctSessionCount()).isEqualTo(2);
      assertThat(progress.status()).isEqualTo(ProgressStatus.LEARNING);
    });
  }

  @Test
  @DisplayName("同一会话同一题同一知识点重评只保留最新评分参与聚合")
  void shouldUseLatestEvaluationForSameQuestionNode() {
    KnowledgeNode node = node("JAVA.JVM", null, true, 0);
    List<AssessmentEvidence> evidence = List.of(
        evidence(7, 3, "s1", 0, node.code(), TagRole.PRIMARY, 20, true, now.minusDays(2), "old"),
        evidence(7, 3, "s1", 0, node.code(), TagRole.PRIMARY, 80, true, now.minusDays(1), "new"),
        evidence(7, 3, "s2", 0, node.code(), TagRole.PRIMARY, 100, true, now, "other")
    );

    NodeProgress result = aggregator.aggregate(7, 3, List.of(node), evidence, now).getFirst();

    assertThat(result.masteryScore()).isEqualTo(90);
    assertThat(result.evidenceCount()).isEqualTo(2);
    assertThat(result.distinctSessionCount()).isEqualTo(2);
    assertThat(result.status()).isEqualTo(ProgressStatus.LEARNING);
  }

  @Test
  @DisplayName("次标签权重为主标签一半，且 90 天半衰期降低陈旧证据影响")
  void shouldWeightSecondaryTagsAndDecayOldEvidence() {
    KnowledgeNode node = node("JAVA.JVM", null, true, 0);
    List<AssessmentEvidence> evidence = List.of(
        evidence(7, 3, "old", 0, node.code(), TagRole.PRIMARY, 0, true, now.minusDays(90), "old"),
        evidence(7, 3, "recent", 0, node.code(), TagRole.SECONDARY, 100, true, now, "recent")
    );

    NodeProgress result = aggregator.aggregate(7, 3, List.of(node), evidence, now).getFirst();

    assertThat(result.masteryScore()).isEqualTo(50);
    assertThat(result.distinctSessionCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("掌握需要至少 3 个不同面试会话，不能由单次高分直接判定")
  void shouldRequireMultipleSessionsBeforeMastered() {
    KnowledgeNode node = node("JAVA.JVM", null, true, 0);
    List<AssessmentEvidence> evidence = List.of(
        evidence(7, 3, "s1", 0, node.code(), TagRole.PRIMARY, 100, true, now, "1"),
        evidence(7, 3, "s2", 0, node.code(), TagRole.PRIMARY, 100, true, now, "2"),
        evidence(7, 3, "s3", 0, node.code(), TagRole.PRIMARY, 100, true, now, "3"));

    NodeProgress result = aggregator.aggregate(7, 3, List.of(node), evidence, now).getFirst();

    assertThat(result.masteryScore()).isEqualTo(100);
    assertThat(result.status()).isEqualTo(ProgressStatus.MASTERED);
  }

  private static KnowledgeNode node(String code, String parent, boolean assignable, int order) {
    return new KnowledgeNode(code, parent, assignable, true, order);
  }

  private static AssessmentEvidence evidence(
      long owner,
      int version,
      String session,
      int questionIndex,
      String node,
      TagRole role,
      int score,
      boolean verified,
      LocalDateTime at,
      String fingerprint
  ) {
    return new AssessmentEvidence(owner, version, "TEXT_INTERVIEW", session, questionIndex, node, role, score,
        verified, at, fingerprint, EVENT_SEQUENCE.incrementAndGet());
  }
}
