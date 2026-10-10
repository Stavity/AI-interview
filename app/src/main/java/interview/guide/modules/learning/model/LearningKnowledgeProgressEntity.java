package interview.guide.modules.learning.model;

import interview.guide.modules.learning.service.LearningEvidenceAggregator.NodeProgress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "learning_knowledge_progress")
@Getter
@NoArgsConstructor
public class LearningKnowledgeProgressEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @Column(name = "taxonomy_version", nullable = false, updatable = false)
  private int taxonomyVersion;

  @Column(name = "node_code", nullable = false, length = 255, updatable = false)
  private String nodeCode;

  @Column(name = "aggregation_rule_version", nullable = false)
  private int aggregationRuleVersion;

  @Column(name = "mastery_score", nullable = false)
  private int masteryScore;

  @Column(name = "evidence_count", nullable = false)
  private int evidenceCount;

  @Column(name = "distinct_session_count", nullable = false)
  private int distinctSessionCount;

  @Column(name = "consecutive_correct_count", nullable = false)
  private int consecutiveCorrectCount;

  @Column(name = "consecutive_incorrect_count", nullable = false)
  private int consecutiveIncorrectCount;

  @Column(name = "confidence_score", nullable = false)
  private int confidenceScore;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "last_practiced_at")
  private LocalDateTime lastPracticedAt;

  @Column(name = "aggregated_at", nullable = false)
  private LocalDateTime aggregatedAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime updatedAt;

  public static LearningKnowledgeProgressEntity from(
      long ownerUserId,
      int taxonomyVersion,
      int aggregationRuleVersion,
      NodeProgress progress,
      LocalDateTime aggregatedAt
  ) {
    LearningKnowledgeProgressEntity entity = new LearningKnowledgeProgressEntity();
    entity.ownerUserId = ownerUserId;
    entity.taxonomyVersion = taxonomyVersion;
    entity.nodeCode = progress.nodeCode();
    entity.aggregationRuleVersion = aggregationRuleVersion;
    entity.masteryScore = progress.masteryScore();
    entity.evidenceCount = progress.evidenceCount();
    entity.distinctSessionCount = progress.distinctSessionCount();
    entity.consecutiveCorrectCount = progress.consecutiveCorrectCount();
    entity.consecutiveIncorrectCount = progress.consecutiveIncorrectCount();
    entity.confidenceScore = progress.confidenceScore();
    entity.status = progress.status().name();
    entity.lastPracticedAt = progress.lastPracticedAt();
    entity.aggregatedAt = aggregatedAt;
    return entity;
  }
}
