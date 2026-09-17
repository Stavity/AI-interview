package interview.guide.common.async;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.tasks.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class AsyncTaskRecoveryScheduler {
    private final AsyncTaskStateService state;
    @Scheduled(fixedDelayString = "${app.tasks.recovery-interval-ms:60000}")
    public void recover() { state.recover(); }
}
