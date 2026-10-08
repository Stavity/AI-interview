package interview.guide.common.async;

import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import interview.guide.common.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service @RequiredArgsConstructor @Slf4j
public class AsyncTaskExecutionService {
    private final AsyncTaskStateService state;
    private final AsyncTaskHandlerRegistry handlers;
    private final KafkaTaskProperties properties;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2,
        Thread.ofPlatform().daemon().name("task-heartbeat-", 0).factory());

    public void execute(TaskEnvelope envelope) {
        var claim = state.claim(envelope);
        if (claim == null) return;
        var completed = new AtomicBoolean();
        var context = new TaskExecutionContext(claim.id(), claim.ownerUserId(), claim.businessId(), claim.version(), claim.input(), write -> {
            state.complete(claim, write);
            completed.set(true);
        });
        var future = workers.submit(() -> {
            TenantContext.runAs(claim.ownerUserId(), () -> {
                var handler = handlers.get(claim.type());
                if (!handler.isCurrent(claim.id(), claim.businessId())) {
                    state.cancel(claim);
                    return;
                }
                handler.updateStatus(claim.businessId(), interview.guide.common.async.AsyncTaskState.RUNNING, null);
                handler.execute(context);
                if (!completed.get()) throw new IllegalStateException("Handler returned without completing task");
            });
        });
        long interval = properties.getHeartbeatInterval().toMillis();
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { if (!state.heartbeat(claim)) future.cancel(true); }
            catch (Exception e) { log.warn("Task heartbeat failed: taskId={}", claim.id()); }
        }, interval, interval, TimeUnit.MILLISECONDS);
        try {
            future.get(properties.executionTimeout(claim.type()).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); future.cancel(true);
            throw new IllegalStateException("Task consumer interrupted", e);
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            future.cancel(true);
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            log.warn("Task execution failed: taskId={}, type={}, cause={}", claim.id(), claim.type(), cause.getClass().getSimpleName());
            TenantContext.runAs(claim.ownerUserId(), () -> state.fail(claim, TaskFailurePolicy.retryable(cause)));
        } finally { heartbeat.cancel(false); }
    }
    @PreDestroy public void shutdown() { workers.shutdownNow(); heartbeats.shutdownNow(); }
}
