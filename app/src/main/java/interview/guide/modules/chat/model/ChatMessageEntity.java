package interview.guide.modules.chat.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageEntity {
  public enum Role { USER, ASSISTANT }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "session_id", nullable = false) private ChatSessionEntity session;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
  @Column(nullable = false, columnDefinition = "TEXT") private String content;
  @Column(name = "message_order", nullable = false) private int messageOrder;
  @Column(nullable = false) private boolean completed = true;
  @Column(name = "failure_code", length = 64) private String failureCode;
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
  @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
