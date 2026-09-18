package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "interview_agent_runs") @Getter @Setter @NoArgsConstructor
public class AgentRunAuditEntity {
  public enum Status { RUNNING, SUCCEEDED, REJECTED, FAILED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "multi_round_interview_id", nullable = false) private MultiRoundInterviewEntity interview;
  @Column(name = "trigger_type", nullable = false, length = 32) private String triggerType;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.RUNNING;
  @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
  @Column(name = "output_json", columnDefinition = "TEXT") private String outputJson;
  @Column(name = "error_code", length = 64) private String errorCode;
  @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
  @Column(name = "finished_at") private LocalDateTime finishedAt;
}
