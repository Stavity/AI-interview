package interview.guide.common.async;
import interview.guide.infrastructure.async.*;
import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class AsyncTaskSubmissionService {
    private final AsyncTaskRepository tasks;
    private final TaskOutboxWriter outbox;
    private final ObjectMapper mapper;
    private final KafkaTaskProperties properties;
    @Transactional
    public UUID submit(AsyncTaskType type, String businessId, Map<String, String> input) {
        return submit(UUID.randomUUID(), type, businessId, input);
    }
    @Transactional
    public UUID submit(UUID id, AsyncTaskType type, String businessId, Map<String, String> input) {
        String key = type + ":" + businessId;
        if (tasks.findByActiveKey(key).isPresent()) {
            throw new interview.guide.common.exception.BusinessException(
                interview.guide.common.exception.ErrorCode.BAD_REQUEST, "任务正在处理中，请勿重复提交");
        }
        var task = new AsyncTaskEntity();
        task.setId(id); task.setTaskType(type); task.setBusinessId(businessId); task.setActiveKey(key);
        task.setState(AsyncTaskState.QUEUED); task.setInputPayload(mapper.writeValueAsString(input));
        task.setMaxAttempts(properties.getMaxAttempts());
        task.setNextRunAt(Instant.now()); task.setCreatedAt(Instant.now()); task.setUpdatedAt(Instant.now());
        tasks.saveAndFlush(task); outbox.enqueue(task, task.getNextRunAt(), false);
        return id;
    }
}
