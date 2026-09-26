package interview.guide.modules.questiontag.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_knowledge_nodes")
@Getter
@Setter
@NoArgsConstructor
public class QuestionKnowledgeNodeEntity {

  public enum Status { ACTIVE, DEPRECATED, ARCHIVED }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "taxonomy_version", nullable = false)
  private Integer taxonomyVersion;

  @Column(nullable = false, length = 255)
  private String code;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(name = "parent_code", length = 255)
  private String parentCode;

  @Column(name = "node_kind", nullable = false, length = 32)
  private String nodeKind;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(nullable = false)
  private boolean assignable;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public static QuestionKnowledgeNodeEntity create(
      int taxonomyVersion,
      String code,
      String name,
      String parentCode,
      String nodeKind,
      String description,
      boolean assignable,
      int sortOrder
  ) {
    QuestionKnowledgeNodeEntity entity = new QuestionKnowledgeNodeEntity();
    entity.taxonomyVersion = taxonomyVersion;
    entity.code = code;
    entity.name = name;
    entity.parentCode = parentCode;
    entity.nodeKind = nodeKind == null || nodeKind.isBlank() ? "CONCEPT" : nodeKind;
    entity.description = description;
    entity.assignable = assignable;
    entity.status = Status.ACTIVE;
    entity.sortOrder = sortOrder;
    return entity;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
