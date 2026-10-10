package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** Immutable resource recommendation frozen with a particular learning-path item. */
@Entity
@Table(name = "learning_path_item_resources")
@Getter
@NoArgsConstructor
public class LearningPathItemResourceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "path_item_id", nullable = false, updatable = false)
  private Long pathItemId;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @Column(name = "skill_source_id", nullable = false, updatable = false)
  private Long skillSourceId;

  @Column(name = "source_display_name", nullable = false, length = 255, updatable = false)
  private String sourceDisplayName;

  @Column(name = "category_key", nullable = false, length = 64, updatable = false)
  private String categoryKey;

  @Column(name = "match_score", nullable = false, updatable = false)
  private int matchScore;

  @Column(name = "match_reason", nullable = false, length = 500, updatable = false)
  private String matchReason;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;

  public static LearningPathItemResourceEntity matched(
      long ownerUserId, long pathItemId, LearningSkillSourceEntity source, int matchScore,
      String matchReason
  ) {
    LearningPathItemResourceEntity entity = new LearningPathItemResourceEntity();
    entity.ownerUserId = ownerUserId;
    entity.pathItemId = pathItemId;
    entity.skillSourceId = source.getId();
    entity.sourceDisplayName = source.getDisplayName();
    entity.categoryKey = source.getCategoryKey();
    entity.matchScore = matchScore;
    entity.matchReason = matchReason;
    return entity;
  }
}
