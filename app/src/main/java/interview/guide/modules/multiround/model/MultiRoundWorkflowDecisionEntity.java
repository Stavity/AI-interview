package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable accepted output of a root/child agent at a workflow checkpoint. */
@Entity
@Table(name = "multi_round_workflow_decisions", uniqueConstraints = @UniqueConstraint(
    columnNames = {"workflow_instance_id", "checkpoint_key", "decision_type"}))
@Getter @Setter @NoArgsConstructor
public class MultiRoundWorkflowDecisionEntity {
  public enum Status { ACCEPTED, REJECTED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workflow_instance_id", nullable = false)
  private MultiRoundWorkflowInstanceEntity workflow;
  @Column(name = "checkpoint_key", nullable = false, length = 64) private String checkpointKey;
  @Column(name = "decision_type", nullable = false, length = 48) private String decisionType;
  @Column(name = "agent_name", nullable = false, length = 64) private String agentName;
  @Column(name = "policy_version", nullable = false, length = 64) private String policyVersion;
  @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
  @Column(name = "decision_json", nullable = false, columnDefinition = "TEXT") private String decisionJson;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "accepted_at") private LocalDateTime acceptedAt;
  @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
}
