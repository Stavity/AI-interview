package interview.guide.modules.questiontag.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_skill_knowledge_scope")
@Getter
@Setter
@NoArgsConstructor
public class QuestionSkillKnowledgeScopeEntity {

  public enum Priority { CORE, NORMAL, OPTIONAL }

  @EmbeddedId
  private Id id;

  @Column(name = "include_descendants", nullable = false)
  private boolean includeDescendants;

  @Column(nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private Priority priority;

  @Column(nullable = false, precision = 6, scale = 3)
  private BigDecimal weight;

  public static QuestionSkillKnowledgeScopeEntity create(
      int taxonomyVersion,
      String skillId,
      String nodeCode,
      boolean includeDescendants,
      Priority priority,
      BigDecimal weight
  ) {
    QuestionSkillKnowledgeScopeEntity entity = new QuestionSkillKnowledgeScopeEntity();
    entity.id = new Id(taxonomyVersion, skillId, nodeCode);
    entity.includeDescendants = includeDescendants;
    entity.priority = priority;
    entity.weight = weight;
    return entity;
  }

  @Embeddable
  @Getter
  @Setter
  @NoArgsConstructor
  @EqualsAndHashCode
  public static class Id implements Serializable {
    @Column(name = "taxonomy_version", nullable = false)
    private Integer taxonomyVersion;

    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId;

    @Column(name = "node_code", nullable = false, length = 255)
    private String nodeCode;

    public Id(Integer taxonomyVersion, String skillId, String nodeCode) {
      this.taxonomyVersion = taxonomyVersion;
      this.skillId = skillId;
      this.nodeCode = nodeCode;
    }
  }
}
