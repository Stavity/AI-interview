package interview.guide.modules.questiontag.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "knowledge_base_question_tags")
@Getter
@Setter
@NoArgsConstructor
public class QuestionKnowledgeTagEntity {

  @EmbeddedId
  private Id id;

  @TenantId
  @Column(name = "owner_user_id", nullable = false, updatable = false)
  private Long ownerUserId;

  @Column(name = "taxonomy_version", nullable = false)
  private Integer taxonomyVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "relation_type", nullable = false, length = 20)
  private QuestionTagRelationType relationType;

  @Column(name = "relevance_weight", nullable = false, precision = 5, scale = 4)
  private BigDecimal relevanceWeight;

  @Enumerated(EnumType.STRING)
  @Column(name = "tagging_source", nullable = false, length = 20)
  private QuestionTaggingSource taggingSource;

  @Column(precision = 5, scale = 4)
  private BigDecimal confidence;

  public static QuestionKnowledgeTagEntity create(
      Long questionId,
      int taxonomyVersion,
      String nodeCode,
      QuestionTagRelationType relationType,
      QuestionTaggingSource taggingSource,
      BigDecimal relevanceWeight,
      BigDecimal confidence
  ) {
    QuestionKnowledgeTagEntity entity = new QuestionKnowledgeTagEntity();
    entity.id = new Id(questionId, nodeCode);
    entity.taxonomyVersion = taxonomyVersion;
    entity.relationType = relationType;
    entity.taggingSource = taggingSource;
    entity.relevanceWeight = relevanceWeight;
    entity.confidence = confidence;
    return entity;
  }

  @Embeddable
  @Getter
  @Setter
  @NoArgsConstructor
  @EqualsAndHashCode
  public static class Id implements Serializable {
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "node_code", nullable = false, length = 255)
    private String nodeCode;

    public Id(Long questionId, String nodeCode) {
      this.questionId = questionId;
      this.nodeCode = nodeCode;
    }
  }
}
