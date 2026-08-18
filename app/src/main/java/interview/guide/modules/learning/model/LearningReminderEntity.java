package interview.guide.modules.learning.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "learning_reminders")
@Getter
@Setter
@NoArgsConstructor
public class LearningReminderEntity {
  public enum Channel { IN_APP, BROWSER }
  public enum Status { PENDING, READY, DELIVERED, CANCELLED, FAILED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "task_id", nullable = false) private LearningPlanTaskEntity task;
  @Column(name = "remind_at", nullable = false) private LocalDateTime remindAt;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Channel channel = Channel.IN_APP;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
  @Column(name = "delivered_at") private LocalDateTime deliveredAt;
  @Column(name = "failure_reason", length = 500) private String failureReason;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  public static LearningReminderEntity pending(LearningPlanTaskEntity task, LocalDateTime remindAt) { LearningReminderEntity e = new LearningReminderEntity(); e.task = task; e.remindAt = remindAt; return e; }
  public boolean readyIfDue(LocalDateTime now) { if (status != Status.PENDING || remindAt.isAfter(now)) return false; status = Status.READY; return true; }
  public boolean acknowledge(Status outcome, String reason) {
    if (status == outcome) return false;
    if (status != Status.READY || (outcome != Status.DELIVERED && outcome != Status.FAILED)) return false;
    status = outcome;
    if (outcome == Status.DELIVERED) { deliveredAt = LocalDateTime.now(); failureReason = null; }
    else failureReason = reason == null || reason.isBlank() ? "BROWSER_DELIVERY_FAILED" : reason.substring(0, Math.min(500, reason.length()));
    return true;
  }
  public void cancelForCompletedTask() { if (status == Status.PENDING || status == Status.READY) status = Status.CANCELLED; }
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
