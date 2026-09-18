package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "algorithm_submissions", uniqueConstraints = {
    @UniqueConstraint(name = "algorithm_submissions_question_unique", columnNames = {"question_id"})
})
@Getter @Setter @NoArgsConstructor
public class AlgorithmSubmissionEntity {
  public enum Language { JAVA, PYTHON, CPP }
  public enum EvaluationStatus { PENDING, COMPLETED, FAILED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "question_id", nullable = false) private MultiRoundQuestionEntity question;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Language language;
  @Column(name = "template_version", nullable = false, length = 32) private String templateVersion = "v1";
  @Column(name = "source_code", nullable = false, columnDefinition = "TEXT") private String sourceCode;
  @Column(name = "evaluation_json", columnDefinition = "TEXT") private String evaluationJson;
  @Enumerated(EnumType.STRING) @Column(name = "evaluation_status", nullable = false, length = 16) private EvaluationStatus evaluationStatus = EvaluationStatus.PENDING;
  @Column(name = "evaluation_error_code", length = 64) private String evaluationErrorCode;
  private Integer score;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
