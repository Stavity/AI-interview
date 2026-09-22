package interview.guide.modules.multiround.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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

/** Append-only business events used for recovery and safe traceability. */
@Entity
@Table(name = "multi_round_workflow_events")
@Getter
@Setter
@NoArgsConstructor
public class MultiRoundWorkflowEventEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "workflow_instance_id", nullable = false)
  private MultiRoundWorkflowInstanceEntity workflow;

  @Column(name = "event_key", nullable = false, length = 160)
  private String eventKey;

  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;

  @Column(name = "checkpoint_key", nullable = false, length = 96)
  private String checkpointKey;

  @Column(name = "payload_json", columnDefinition = "TEXT")
  private String payloadJson;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = LocalDateTime.now();
  }
}
