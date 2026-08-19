package interview.guide.modules.multiround.model;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "multi_round_interviews")
@Getter @Setter @NoArgsConstructor
public class MultiRoundInterviewEntity {
  public enum Status { CREATED, ROUND_READY, IN_PROGRESS, ROUND_EVALUATING, ROUND_COMPLETED, COMPLETED, FAILED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "interview_session_id", nullable = false, unique = true) private InterviewSessionEntity interviewSession;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Status status = Status.CREATED;
  @Column(name = "current_round_no", nullable = false) private int currentRoundNo;
  @Column(name = "provider_id", length = 64) private String providerId;
  @Column(name = "provider_fingerprint", length = 64) private String providerFingerprint;
  @Column(name = "resume_snapshot", columnDefinition = "TEXT") private String resumeSnapshot;
  @Column(name = "resume_snapshot_version", length = 64) private String resumeSnapshotVersion;
  @Column(name = "jd_snapshot", columnDefinition = "TEXT") private String jdSnapshot;
  @Column(name = "skill_id", length = 64) private String skillId;
  @Column(length = 16) private String difficulty;
  @Column(name = "scope_fingerprint", length = 64) private String scopeFingerprint;
  @Column(name = "started_at") private LocalDateTime startedAt;
  @Column(name = "completed_at") private LocalDateTime completedAt;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
