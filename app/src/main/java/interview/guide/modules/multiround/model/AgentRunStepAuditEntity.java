package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "interview_agent_steps")
@Getter
@Setter
@NoArgsConstructor
public class AgentRunStepAuditEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "run_id", nullable = false)
  private AgentRunAuditEntity run;

  @Column(name = "step_no", nullable = false)
  private Integer stepNo;

  @Column(name = "tool_name", nullable = false, length = 64)
  private String toolName;

  @Column(name = "input_summary", length = 500)
  private String inputSummary;

  @Column(name = "output_summary", length = 500)
  private String outputSummary;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void initializeCreatedAt() {
    if (createdAt == null) createdAt = LocalDateTime.now();
  }
}
