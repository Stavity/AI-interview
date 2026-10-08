package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.TenantId;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "learning_progress", uniqueConstraints = {
    @jakarta.persistence.UniqueConstraint(
        name = "uk_learning_progress_owner_source",
        columnNames = {"owner_user_id", "skill_source_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class LearningProgressEntity {

  public enum Status {
    NOT_STARTED,
    LEARNING,
    MASTERED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "skill_source_id", nullable = false)
  private LearningSkillSourceEntity skillSource;

  @Column(name = "mastery_score", nullable = false)
  private int masteryScore;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "last_practiced_at")
  private LocalDateTime lastPracticedAt;

  @Version
  @Column(nullable = false)
  private Long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public static LearningProgressEntity create(LearningSkillSourceEntity skillSource) {
    LearningProgressEntity entity = new LearningProgressEntity();
    entity.skillSource = skillSource;
    entity.masteryScore = 0;
    entity.status = Status.NOT_STARTED;
    return entity;
  }

  public void adjustMastery(int delta) {
    masteryScore = Math.max(0, Math.min(100, masteryScore + delta));
    if (masteryScore == 0) {
      status = Status.NOT_STARTED;
      return;
    }
    status = masteryScore == 100 ? Status.MASTERED : Status.LEARNING;
    lastPracticedAt = LocalDateTime.now();
  }

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
