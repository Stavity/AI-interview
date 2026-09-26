package interview.guide.modules.questiontag.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_taxonomy_versions")
@Getter
@Setter
@NoArgsConstructor
public class QuestionTaxonomyVersionEntity {

  public enum Status { ACTIVE, ARCHIVED }

  @Id
  private Integer version;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public static QuestionTaxonomyVersionEntity active(int version, String contentHash) {
    QuestionTaxonomyVersionEntity entity = new QuestionTaxonomyVersionEntity();
    entity.version = version;
    entity.status = Status.ACTIVE;
    entity.contentHash = contentHash;
    entity.publishedAt = LocalDateTime.now();
    return entity;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
