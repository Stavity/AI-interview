package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "multi_round_questions") @Getter @Setter @NoArgsConstructor
public class MultiRoundQuestionEntity {
  public enum Status { PENDING, ANSWERED, EVALUATED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "round_id", nullable = false) private MultiRoundEntity round;
  @Column(name = "sequence_no", nullable = false) private int sequenceNo;
  @Column(name = "question_type", nullable = false) private String questionType;
  @Column(name = "category_key") private String categoryKey;
  @Column(nullable = false, columnDefinition = "TEXT") private String question;
  @Column(name = "reference_answer", columnDefinition = "TEXT") private String referenceAnswer;
  @Column(columnDefinition = "TEXT") private String answer;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
  @Column(name = "follow_up_count", nullable = false) private int followUpCount;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
