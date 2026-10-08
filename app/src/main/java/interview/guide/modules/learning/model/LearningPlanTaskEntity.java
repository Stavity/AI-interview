package interview.guide.modules.learning.model;

import jakarta.persistence.*;
import org.hibernate.annotations.TenantId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "learning_plan_tasks")
@Getter
@Setter
@NoArgsConstructor
public class LearningPlanTaskEntity {
  public enum Status { PENDING, COMPLETED, CANCELLED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @TenantId @Column(name = "owner_user_id", nullable = false, updatable = false) private Long ownerUserId;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "plan_id", nullable = false) private LearningPlanEntity plan;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "skill_source_id") private LearningSkillSourceEntity skillSource;
  @Column(name = "due_date", nullable = false) private LocalDate dueDate;
  @Column(name = "task_type", nullable = false, length = 32) private String taskType = "STUDY";
  @Column(nullable = false) private String title;
  @Column(name = "estimated_minutes", nullable = false) private int estimatedMinutes = 30;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
  @Column(name = "completed_at") private LocalDateTime completedAt;
  @Column(name = "sort_order", nullable = false) private int sortOrder;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  public static LearningPlanTaskEntity create(String title, LocalDate dueDate) { LearningPlanTaskEntity e = new LearningPlanTaskEntity(); e.title = title; e.dueDate = dueDate; return e; }
  public void complete() { if (status == Status.PENDING) { status = Status.COMPLETED; completedAt = LocalDateTime.now(); } }
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
