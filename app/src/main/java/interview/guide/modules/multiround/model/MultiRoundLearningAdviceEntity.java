package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;

@Entity @Table(name = "multi_round_learning_advice") @Getter @Setter @NoArgsConstructor
public class MultiRoundLearningAdviceEntity {
  public enum Source { GOOGLE_ADK, DETERMINISTIC }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "multi_round_interview_id", nullable = false, unique = true)
  private MultiRoundInterviewEntity interview;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Source source;
  @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
  @Column(name = "advice_json", nullable = false, columnDefinition = "TEXT") private String adviceJson;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
}
