package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "learning_path_items")
@Getter
@Setter
@NoArgsConstructor
public class LearningPathItemEntity {

  public enum Status { PENDING, COMPLETED, SKIPPED }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "path_id", nullable = false, updatable = false)
  private Long pathId;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @Column(name = "taxonomy_version", nullable = false, updatable = false)
  private int taxonomyVersion;

  @Column(name = "node_code", nullable = false, length = 255, updatable = false)
  private String nodeCode;

  @Column(name = "sequence_no", nullable = false, updatable = false)
  private int sequenceNo;

  @Column(name = "node_name", nullable = false, length = 255, updatable = false)
  private String nodeName;

  @Column(name = "mastery_score", nullable = false, updatable = false)
  private int masteryScore;

  @Column(name = "evidence_count", nullable = false, updatable = false)
  private int evidenceCount;

  @Column(nullable = false, length = 32, updatable = false)
  private String difficulty;

  @Column(name = "estimated_minutes", nullable = false, updatable = false)
  private int estimatedMinutes;

  @Column(nullable = false, updatable = false)
  private boolean prerequisite;

  @Column(name = "recommendation_reason", nullable = false, length = 1000, updatable = false)
  private String recommendationReason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;

  public static LearningPathItemEntity pending(
      long ownerUserId, long pathId, int taxonomyVersion, int sequenceNo, String nodeCode,
      String nodeName, int masteryScore, int evidenceCount, String difficulty,
      int estimatedMinutes, boolean prerequisite, String recommendationReason
  ) {
    LearningPathItemEntity entity = new LearningPathItemEntity();
    entity.ownerUserId = ownerUserId;
    entity.pathId = pathId;
    entity.taxonomyVersion = taxonomyVersion;
    entity.sequenceNo = sequenceNo;
    entity.nodeCode = nodeCode;
    entity.nodeName = nodeName;
    entity.masteryScore = masteryScore;
    entity.evidenceCount = evidenceCount;
    entity.difficulty = difficulty;
    entity.estimatedMinutes = estimatedMinutes;
    entity.prerequisite = prerequisite;
    entity.recommendationReason = recommendationReason;
    entity.status = Status.PENDING;
    return entity;
  }

  public void complete() {
    if (status == Status.PENDING) {
      status = Status.COMPLETED;
      completedAt = LocalDateTime.now();
    }
  }
}
