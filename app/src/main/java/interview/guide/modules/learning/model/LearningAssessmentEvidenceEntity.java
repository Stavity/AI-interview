package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "learning_assessment_evidence")
public class LearningAssessmentEvidenceEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType;

  @Column(name = "source_session_id", nullable = false, length = 64)
  private String sourceSessionId;

  @Column(name = "source_answer_id", nullable = false)
  private Long sourceAnswerId;

  @Column(name = "source_task_id")
  private UUID sourceTaskId;

  @Column(name = "evaluation_version", nullable = false)
  private long evaluationVersion;

  @Column(name = "question_index", nullable = false)
  private int questionIndex;

  @Column(name = "taxonomy_version", nullable = false)
  private int taxonomyVersion;

  @Column(name = "node_code", nullable = false, length = 255)
  private String nodeCode;

  @Column(name = "tag_role", nullable = false, length = 20)
  private String tagRole;

  @Column(nullable = false)
  private int score;

  @Column(nullable = false)
  private boolean verified;

  @Column(name = "assessed_at", nullable = false)
  private LocalDateTime assessedAt;

  @Column(name = "evaluation_fingerprint", nullable = false, length = 64)
  private String evaluationFingerprint;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public Long getOwnerUserId() { return ownerUserId; }
  public String getSourceType() { return sourceType; }
  public String getSourceSessionId() { return sourceSessionId; }
  public Long getSourceAnswerId() { return sourceAnswerId; }
  public UUID getSourceTaskId() { return sourceTaskId; }
  public long getEvaluationVersion() { return evaluationVersion; }
  public int getQuestionIndex() { return questionIndex; }
  public int getTaxonomyVersion() { return taxonomyVersion; }
  public String getNodeCode() { return nodeCode; }
  public String getTagRole() { return tagRole; }
  public int getScore() { return score; }
  public boolean isVerified() { return verified; }
  public LocalDateTime getAssessedAt() { return assessedAt; }
  public String getEvaluationFingerprint() { return evaluationFingerprint; }
  public LocalDateTime getCreatedAt() { return createdAt; }
}
