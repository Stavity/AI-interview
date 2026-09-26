package interview.guide.modules.questiontag.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_knowledge_node_relations")
@Getter
@Setter
@NoArgsConstructor
public class QuestionKnowledgeNodeRelationEntity {

  @EmbeddedId
  private Id id;

  public enum RelationType { RELATED_TO, PREREQUISITE_OF, SUPERSEDES }

  public static QuestionKnowledgeNodeRelationEntity create(
      int taxonomyVersion, String sourceCode, String targetCode, RelationType relationType
  ) {
    QuestionKnowledgeNodeRelationEntity entity = new QuestionKnowledgeNodeRelationEntity();
    entity.id = new Id(taxonomyVersion, sourceCode, targetCode, relationType);
    return entity;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @EqualsAndHashCode
  @jakarta.persistence.Embeddable
  public static class Id implements Serializable {
    @Column(name = "taxonomy_version", nullable = false)
    private Integer taxonomyVersion;

    @Column(name = "source_code", nullable = false, length = 255)
    private String sourceCode;

    @Column(name = "target_code", nullable = false, length = 255)
    private String targetCode;

    @Column(name = "relation_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private RelationType relationType;

    public Id(Integer taxonomyVersion, String sourceCode, String targetCode, RelationType relationType) {
      this.taxonomyVersion = taxonomyVersion;
      this.sourceCode = sourceCode;
      this.targetCode = targetCode;
      this.relationType = relationType;
    }
  }
}
