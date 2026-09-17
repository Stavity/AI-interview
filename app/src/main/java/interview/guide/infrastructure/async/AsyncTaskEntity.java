package interview.guide.infrastructure.async;
import interview.guide.common.async.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "async_task") @Getter @Setter
public class AsyncTaskEntity {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private AsyncTaskType taskType;
    @Column(nullable = false, length = 128) private String businessId;
    @Column(unique = true, length = 200) private String activeKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AsyncTaskState state;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String inputPayload;
    @Column(nullable = false) private int attemptCount;
    @Column(nullable = false) private int maxAttempts;
    @Column(nullable = false) private long executionVersion;
    private Instant leaseUntil;
    private Instant executionDeadline;
    @Column(nullable = false) private Instant nextRunAt;
    @Column(length = 500) private String errorMessage;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
}
