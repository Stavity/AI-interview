package interview.guide.common.async;
import interview.guide.infrastructure.async.*;
import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
@Component @RequiredArgsConstructor
public class TaskOutboxWriter {
    private final TaskOutboxRepository outbox;
    private final ObjectMapper mapper;
    public void enqueue(AsyncTaskEntity task, Instant availableAt, boolean deadLetter) {
        var row = new TaskOutboxEntity();
        row.setId(UUID.randomUUID()); row.setTaskId(task.getId());
        row.setTopic(deadLetter ? KafkaTaskProperties.DLT : task.getTaskType().topic());
        row.setMessageKey(task.getTaskType() + ":" + task.getBusinessId());
        var envelope = new TaskEnvelope(1, row.getId(), task.getId(), task.getTaskType(), task.getBusinessId(), Instant.now());
        row.setPayload(mapper.writeValueAsString(deadLetter
            ? Map.of("event", envelope, "reason", "TASK_FAILED", "attempts", task.getAttemptCount()) : envelope));
        row.setState("PENDING"); row.setAvailableAt(availableAt); row.setCreatedAt(Instant.now());
        outbox.save(row);
    }
}
