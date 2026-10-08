package interview.guide.modules.learning.model;

import jakarta.persistence.*;
import org.hibernate.annotations.TenantId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "learning_plans")
@Getter
@Setter
@NoArgsConstructor
public class LearningPlanEntity {
  public enum Status { DRAFT, ACTIVE, COMPLETED, ARCHIVED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @TenantId @Column(name = "owner_user_id", nullable = false, updatable = false) private Long ownerUserId;
  @Column(nullable = false) private String title;
  @Column(columnDefinition = "TEXT") private String goal;
  @Column(name = "start_date", nullable = false) private LocalDate startDate;
  @Column(name = "end_date", nullable = false) private LocalDate endDate;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.DRAFT;
  @Column(name = "provider_id", length = 64) private String providerId;
  @Column(name = "provider_fingerprint", length = 64) private String providerFingerprint;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
