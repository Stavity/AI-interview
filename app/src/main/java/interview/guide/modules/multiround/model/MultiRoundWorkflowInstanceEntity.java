package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable execution cursor. Business entities remain the sole source of truth for answers and scores. */
@Entity
@Table(name = "multi_round_workflow_instances")
@Getter @Setter @NoArgsConstructor
public class MultiRoundWorkflowInstanceEntity {
  public enum Status { READY, RUNNING, PAUSED, COMPLETED, FAILED }

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "multi_round_interview_id", nullable = false, unique = true)
  private MultiRoundInterviewEntity interview;
  @Enumerated(EnumType.STRING) @Column(name = "execution_mode", nullable = false, length = 32)
  private MultiRoundInterviewEntity.OrchestrationMode executionMode;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status = Status.READY;
  @Column(name = "definition_version", nullable = false, length = 32) private String definitionVersion;
  @Column(name = "definition_json", nullable = false, columnDefinition = "TEXT") private String definitionJson;
  @Column(name = "state_json", nullable = false, columnDefinition = "TEXT") private String stateJson;
  @Column(name = "checkpoint_key", nullable = false, length = 64) private String checkpointKey;
  @Column(name = "adk_session_id", length = 128) private String adkSessionId;
  @Column(name = "lease_until") private LocalDateTime leaseUntil;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { var now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
