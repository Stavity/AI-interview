package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "learning_paths")
@Getter
@Setter
@NoArgsConstructor
public class LearningPathEntity {

  public enum Status { ACTIVE, SUPERSEDED }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @Column(name = "taxonomy_version", nullable = false, updatable = false)
  private int taxonomyVersion;

  @Column(name = "route_rule_version", nullable = false, updatable = false)
  private int routeRuleVersion;

  @Column(columnDefinition = "TEXT")
  private String goal;

  @Column(name = "trigger_reason", nullable = false, length = 32, updatable = false)
  private String triggerReason;

  @Column(nullable = false, length = 1000)
  private String summary;

  @Column(name = "composition_source", nullable = false, length = 32, updatable = false)
  private String compositionSource;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "superseded_at")
  private LocalDateTime supersededAt;

  public static LearningPathEntity active(
      long ownerUserId, int taxonomyVersion, int routeRuleVersion, String goal,
      String triggerReason, String summary, String compositionSource
  ) {
    LearningPathEntity entity = new LearningPathEntity();
    entity.ownerUserId = ownerUserId;
    entity.taxonomyVersion = taxonomyVersion;
    entity.routeRuleVersion = routeRuleVersion;
    entity.goal = goal;
    entity.triggerReason = triggerReason;
    entity.summary = summary;
    entity.compositionSource = compositionSource;
    entity.status = Status.ACTIVE;
    return entity;
  }

  public void supersede() {
    status = Status.SUPERSEDED;
    supersededAt = LocalDateTime.now();
  }
}
