package interview.guide.modules.multiround.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "multi_round_final_reports")
@Getter @Setter @NoArgsConstructor
public class MultiRoundFinalReportEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "multi_round_interview_id", nullable = false, unique = true)
  private MultiRoundInterviewEntity interview;
  @Column(nullable = false, length = 32) private String source;
  @Column(name = "report_json", nullable = false, columnDefinition = "TEXT") private String reportJson;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { var now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
