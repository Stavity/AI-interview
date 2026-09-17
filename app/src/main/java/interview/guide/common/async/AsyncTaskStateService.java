package interview.guide.common.async;

import interview.guide.infrastructure.async.*;
import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service @RequiredArgsConstructor
public class AsyncTaskStateService {
    private final AsyncTaskRepository tasks;
    private final TaskOutboxRepository outboxRows;
    private final TaskOutboxWriter outbox;
    private final AsyncTaskHandlerRegistry handlers;
    private final KafkaTaskProperties properties;
    private final ObjectMapper mapper;

    public record Claim(UUID id, AsyncTaskType type, String businessId, long version, Map<String, String> input) {}

    @Transactional
    public Claim claim(TaskEnvelope event) {
        var task = tasks.lock(event.taskId()).orElseThrow(() -> new IllegalArgumentException("Unknown task"));
        if (task.getTaskType() != event.taskType() || !task.getBusinessId().equals(event.businessId()))
            throw new IllegalArgumentException("Task envelope does not match persisted task");
        if ((task.getState() != AsyncTaskState.QUEUED && task.getState() != AsyncTaskState.RETRY_WAIT)
                || task.getNextRunAt().isAfter(Instant.now())) return null;
        if (!handlers.get(task.getTaskType()).isCurrent(task.getId(), task.getBusinessId())) {
            cancel(task); return null;
        }
        task.setState(AsyncTaskState.RUNNING);
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setExecutionVersion(task.getExecutionVersion() + 1);
        task.setExecutionDeadline(Instant.now().plus(properties.executionTimeout(task.getTaskType())));
        task.setLeaseUntil(Instant.now().plus(properties.getLeaseDuration()));
        task.setUpdatedAt(Instant.now());
        handlers.get(task.getTaskType()).updateStatus(task.getBusinessId(), AsyncTaskState.RUNNING, null);
        return new Claim(task.getId(), task.getTaskType(), task.getBusinessId(), task.getExecutionVersion(),
            mapper.readValue(task.getInputPayload(), new TypeReference<Map<String, String>>() {}));
    }

    @Transactional
    public boolean heartbeat(Claim claim) {
        var task = tasks.lock(claim.id()).orElse(null);
        if (!owns(task, claim) || task.getExecutionDeadline().isBefore(Instant.now())
                || task.getLeaseUntil().isBefore(Instant.now())) return false;
        var renewed = Instant.now().plus(properties.getLeaseDuration());
        task.setLeaseUntil(renewed.isBefore(task.getExecutionDeadline()) ? renewed : task.getExecutionDeadline());
        return true;
    }

    @Transactional
    public void complete(Claim claim, Runnable writeResult) {
        var task = tasks.lock(claim.id()).orElseThrow();
        if (!owns(task, claim) || task.getLeaseUntil().isBefore(Instant.now())
                || task.getExecutionDeadline().isBefore(Instant.now()))
            throw new StaleTaskExecutionException();
        var handler = handlers.get(task.getTaskType());
        if (!handler.isCurrent(task.getId(), task.getBusinessId())) { cancel(task); return; }
        writeResult.run();
        handler.updateStatus(task.getBusinessId(), AsyncTaskState.SUCCEEDED, null);
        task.setState(AsyncTaskState.SUCCEEDED); task.setActiveKey(null);
        task.setLeaseUntil(null); task.setUpdatedAt(Instant.now()); task.setErrorMessage(null);
    }

    @Transactional
    public void fail(Claim claim, boolean retryable) {
        var task = tasks.lock(claim.id()).orElse(null);
        if (!owns(task, claim)) return;
        retryOrFail(task, retryable);
    }

    @Transactional
    public void recover() {
        for (var task : tasks.lockRecoverable()) {
            if (task.getState() == AsyncTaskState.RUNNING) {
                // Fence the previous execution before issuing another notification.
                task.setExecutionVersion(task.getExecutionVersion() + 1);
                retryOrFail(task, true);
            } else if (!outboxRows.existsByTaskIdAndTopicAndStateNot(task.getId(), task.getTaskType().topic(), "SENT")) {
                outbox.enqueue(task, Instant.now(), false);
                task.setUpdatedAt(Instant.now());
            }
        }
    }

    private void retryOrFail(AsyncTaskEntity task, boolean retryable) {
        var handler = handlers.get(task.getTaskType());
        if (!handler.isCurrent(task.getId(), task.getBusinessId())) { cancel(task); return; }
        task.setLeaseUntil(null); task.setUpdatedAt(Instant.now());
        if (retryable && task.getAttemptCount() < task.getMaxAttempts()) {
            long[] delays = {30, 120, 600};
            long seconds = delays[Math.min(Math.max(task.getAttemptCount() - 1, 0), delays.length - 1)];
            task.setState(AsyncTaskState.RETRY_WAIT);
            task.setNextRunAt(Instant.now().plusSeconds(seconds + ThreadLocalRandom.current().nextLong(10)));
            handler.updateStatus(task.getBusinessId(), AsyncTaskState.RETRY_WAIT, null);
            outbox.enqueue(task, task.getNextRunAt(), false);
        } else {
            task.setState(AsyncTaskState.FAILED); task.setActiveKey(null);
            task.setErrorMessage("任务执行失败，请稍后重试");
            handler.updateStatus(task.getBusinessId(), AsyncTaskState.FAILED, task.getErrorMessage());
            outbox.enqueue(task, Instant.now(), true);
        }
    }

    private boolean owns(AsyncTaskEntity task, Claim claim) {
        return task != null && task.getState() == AsyncTaskState.RUNNING && task.getExecutionVersion() == claim.version();
    }
    private void cancel(AsyncTaskEntity task) {
        task.setState(AsyncTaskState.CANCELLED); task.setActiveKey(null);
        task.setLeaseUntil(null); task.setUpdatedAt(Instant.now());
    }
    public static class StaleTaskExecutionException extends RuntimeException {}
}
