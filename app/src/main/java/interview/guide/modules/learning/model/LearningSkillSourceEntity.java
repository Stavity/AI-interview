package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "learning_skill_sources",
    uniqueConstraints = @UniqueConstraint(name = "uk_learning_skill_source", columnNames = {"skill_id", "category_key"})
)
@Getter
@Setter
@NoArgsConstructor
public class LearningSkillSourceEntity {

  public enum Status {
    ACTIVE,
    UNAVAILABLE
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId;

  @Column(name = "category_key", nullable = false, length = 64)
  private String categoryKey;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "synced_at", nullable = false)
  private LocalDateTime syncedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public static LearningSkillSourceEntity active(
      String skillId,
      String categoryKey,
      String displayName,
      String description,
      String contentHash
  ) {
    LearningSkillSourceEntity entity = new LearningSkillSourceEntity();
    entity.skillId = skillId;
    entity.categoryKey = categoryKey;
    entity.displayName = displayName;
    entity.description = description;
    entity.contentHash = contentHash;
    entity.status = Status.ACTIVE;
    return entity;
  }

  public boolean applyActiveSnapshot(String displayName, String description, String contentHash) {
    boolean changed = status != Status.ACTIVE
        || !this.displayName.equals(displayName)
        || !java.util.Objects.equals(this.description, description)
        || !this.contentHash.equals(contentHash);
    if (changed) {
      this.displayName = displayName;
      this.description = description;
      this.contentHash = contentHash;
      this.status = Status.ACTIVE;
      this.syncedAt = LocalDateTime.now();
    }
    return changed;
  }

  public boolean markUnavailable() {
    if (status == Status.UNAVAILABLE) {
      return false;
    }
    status = Status.UNAVAILABLE;
    syncedAt = LocalDateTime.now();
    return true;
  }

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    if (syncedAt == null) {
      syncedAt = now;
    }
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
