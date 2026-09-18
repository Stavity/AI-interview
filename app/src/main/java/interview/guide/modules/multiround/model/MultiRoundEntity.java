package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "multi_rounds")
@Getter @Setter @NoArgsConstructor
public class MultiRoundEntity {
  public enum Type { ALGORITHM, TECHNICAL_1, TECHNICAL_2, HR }
  public enum Status { PENDING, IN_PROGRESS, COMPLETED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "multi_round_interview_id", nullable = false) private MultiRoundInterviewEntity interview;
  @Column(name = "round_no", nullable = false) private int roundNo;
  @Enumerated(EnumType.STRING) @Column(name = "round_type", nullable = false, length = 32) private Type roundType;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Status status = Status.PENDING;
  @Column(name = "question_limit", nullable = false) private int questionLimit;
  @Column(name = "started_at") private LocalDateTime startedAt;
  @Column(name = "completed_at") private LocalDateTime completedAt;
  @Column(name = "report_json", columnDefinition = "TEXT") private String reportJson;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
