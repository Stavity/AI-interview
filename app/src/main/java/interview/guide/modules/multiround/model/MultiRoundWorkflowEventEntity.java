package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "multi_round_workflow_events")
@Getter @Setter @NoArgsConstructor
public class MultiRoundWorkflowEventEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workflow_instance_id", nullable = false)
  private MultiRoundWorkflowInstanceEntity workflow;
  @Column(name = "event_type", nullable = false, length = 48) private String eventType;
  @Column(name = "checkpoint_key", nullable = false, length = 64) private String checkpointKey;
  @Column(name = "payload_json", columnDefinition = "TEXT") private String payloadJson;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
}
