package interview.guide.modules.multiround.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable audit record for one proposed workflow graph or checkpoint mutation. */
@Entity
@Table(name = "multi_round_workflow_decisions")
@Getter
@Setter
@NoArgsConstructor
public class MultiRoundWorkflowDecisionEntity {
  public enum Status { ACCEPTED, REJECTED }

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
  @JoinColumn(name = "workflow_instance_id", nullable = false)
  private MultiRoundWorkflowInstanceEntity workflow;

  @Column(name = "decision_key", nullable = false, length = 160)
  private String decisionKey;
  @Column(name = "decision_type", nullable = false, length = 48)
  private String decisionType;
  @Column(name = "agent_name", nullable = false, length = 64)
  private String agentName;
  @Column(name = "policy_version", nullable = false, length = 64)
  private String policyVersion;
  @Column(name = "input_hash", nullable = false, length = 64)
  private String inputHash;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
  private Status status;
  @Column(name = "rejection_reason", length = 128)
  private String rejectionReason;
  @Column(name = "decision_json", nullable = false, columnDefinition = "TEXT")
  private String decisionJson;
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
  @Column(name = "accepted_at")
  private LocalDateTime acceptedAt;

  @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
}
