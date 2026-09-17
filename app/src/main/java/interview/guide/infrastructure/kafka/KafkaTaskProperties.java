package interview.guide.infrastructure.kafka;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
@Component @ConfigurationProperties("app.tasks") @Getter @Setter
public class KafkaTaskProperties {
    private int maxAttempts = 4;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration executionTimeout = Duration.ofMinutes(20);
    private Duration heartbeatInterval = Duration.ofSeconds(20);
    private Duration sendTimeout = Duration.ofSeconds(10);
    private int partitions = 3;
    private int replicas = 1;
    private java.util.Map<interview.guide.common.async.AsyncTaskType, Duration> executionTimeouts = new java.util.EnumMap<>(interview.guide.common.async.AsyncTaskType.class);
    public Duration executionTimeout(interview.guide.common.async.AsyncTaskType type) {
        return executionTimeouts.getOrDefault(type, executionTimeout);
    }
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (maxAttempts < 1 || partitions < 1 || replicas < 1
                || heartbeatInterval.isNegative() || heartbeatInterval.isZero()
                || leaseDuration.compareTo(heartbeatInterval.multipliedBy(2)) < 0
                || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid Kafka task configuration");
        }
        for (var type : interview.guide.common.async.AsyncTaskType.values()) {
            var timeout = executionTimeout(type);
            if (timeout.compareTo(leaseDuration) < 0 || timeout.compareTo(Duration.ofMinutes(25)) > 0)
                throw new IllegalArgumentException("Task timeout must be between lease duration and 25m (poll interval is 30m)");
        }
    }
    public static final String DLT = "ai-interview.task-dlt.v1";
}
