package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "multi_round_idempotency") @Getter @Setter @NoArgsConstructor
public class MultiRoundIdempotencyEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
  @Column(name = "operation_type", nullable = false, length = 32) private String operationType;
  @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "multi_round_interview_id") private MultiRoundInterviewEntity interview;
  @Column(name = "resource_type", length = 32) private String resourceType;
  @Column(name = "resource_id") private Long resourceId;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
}
