package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 列长度与 uniqueConstraints 必须与 V20260822__add_multi_round_schema.sql 保持一致：
// 注意 ddl-auto=validate 不校验列长度（Hibernate 的 hasMatchingType 会剥掉 "(n)"），
// 长度漂移由 MultiRoundSchemaDriftTest 离线比对保障。
@Entity
@Table(name = "multi_round_questions", uniqueConstraints = {
    @UniqueConstraint(name = "multi_round_questions_unique_sequence", columnNames = {"round_id", "sequence_no"})
})
@Getter @Setter @NoArgsConstructor
public class MultiRoundQuestionEntity {
  public enum Status { PENDING, ANSWERED, EVALUATED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "round_id", nullable = false) private MultiRoundEntity round;
  @Column(name = "sequence_no", nullable = false) private int sequenceNo;
  @Column(name = "question_type", nullable = false, length = 32) private String questionType;
  @Column(name = "category_key", length = 128) private String categoryKey;
  @Column(nullable = false, columnDefinition = "TEXT") private String question;
  @Column(name = "reference_answer", columnDefinition = "TEXT") private String referenceAnswer;
  @Column(columnDefinition = "TEXT") private String answer;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Status status = Status.PENDING;
  @Column(name = "follow_up_count", nullable = false) private int followUpCount;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
