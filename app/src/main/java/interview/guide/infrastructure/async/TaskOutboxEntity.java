package interview.guide.infrastructure.async;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "task_outbox") @Getter @Setter
public class TaskOutboxEntity {
    @Id private UUID id;
    @Column(nullable = false) private UUID taskId;
    @Column(nullable = false, length = 120) private String topic;
    @Column(nullable = false, length = 200) private String messageKey;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(nullable = false, length = 20) private String state;
    @Column(nullable = false) private Instant availableAt;
    private UUID leaseOwner;
    private Instant leaseUntil;
    @Column(nullable = false) private int publishAttempts;
    @Column(nullable = false) private Instant createdAt;
    private Instant sentAt;
}
