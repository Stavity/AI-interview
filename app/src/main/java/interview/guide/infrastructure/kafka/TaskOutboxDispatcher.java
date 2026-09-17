package interview.guide.infrastructure.kafka;
import interview.guide.infrastructure.async.*;
import interview.guide.common.transaction.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "app.tasks.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class TaskOutboxDispatcher {
    private final TaskOutboxRepository repository;
    private final TransactionalExecutor tx;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaTaskProperties properties;
    @Scheduled(fixedDelayString = "${app.tasks.dispatch-interval-ms:1000}")
    public void dispatch() {
        UUID owner = UUID.randomUUID();
        var batch = tx.call(() -> {
            var rows = repository.lockReady();
            // Covers the entire sequential batch, including each send timeout.
            var expires = Instant.now().plus(properties.getSendTimeout().plusSeconds(10).multipliedBy(rows.size() + 2));
            rows.forEach(row -> {
                row.setState("SENDING"); row.setLeaseOwner(owner); row.setLeaseUntil(expires);
                row.setPublishAttempts(row.getPublishAttempts() + 1);
            });
            return rows;
        });
        for (var row : batch) {
            try {
                kafka.send(row.getTopic(), row.getMessageKey(), row.getPayload())
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                tx.run(() -> repository.markSent(row.getId(), owner, Instant.now()));
            } catch (Exception e) {
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
                log.warn("Task notification publish failed: eventId={}", row.getId());
                tx.run(() -> repository.release(row.getId(), owner,
                    Instant.now().plusSeconds(Math.min(300, row.getPublishAttempts() * 10L))));
            }
        }
    }
}
