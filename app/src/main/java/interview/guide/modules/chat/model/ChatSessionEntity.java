package interview.guide.modules.chat.model;

import jakarta.persistence.*;
import org.hibernate.annotations.TenantId;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
public class ChatSessionEntity {
  public enum Status { ACTIVE, ARCHIVED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @TenantId @Column(name = "owner_user_id", nullable = false, updatable = false) private Long ownerUserId;
  @Column(nullable = false) private String title;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.ACTIVE;
  @Column(name = "is_pinned", nullable = false) private boolean pinned;
  @Column(name = "provider_id", nullable = false, length = 64) private String providerId;
  @Column(name = "provider_fingerprint", nullable = false, length = 64) private String providerFingerprint;
  @Column(name = "message_count", nullable = false) private int messageCount;
  @Column(name = "summary") private String summary;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
