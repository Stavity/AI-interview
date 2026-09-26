package interview.guide.modules.questiontag.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_knowledge_node_aliases")
@Getter
@Setter
@NoArgsConstructor
public class QuestionKnowledgeNodeAliasEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "taxonomy_version", nullable = false)
  private Integer taxonomyVersion;

  @Column(name = "node_code", nullable = false, length = 255)
  private String nodeCode;

  @Column(nullable = false, length = 255)
  private String alias;

  @Column(name = "normalized_alias", nullable = false, length = 255)
  private String normalizedAlias;

  public static QuestionKnowledgeNodeAliasEntity create(
      int taxonomyVersion, String nodeCode, String alias, String normalizedAlias
  ) {
    QuestionKnowledgeNodeAliasEntity entity = new QuestionKnowledgeNodeAliasEntity();
    entity.taxonomyVersion = taxonomyVersion;
    entity.nodeCode = nodeCode;
    entity.alias = alias;
    entity.normalizedAlias = normalizedAlias;
    return entity;
  }
}
