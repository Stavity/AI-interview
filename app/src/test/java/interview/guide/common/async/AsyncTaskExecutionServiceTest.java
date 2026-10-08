package interview.guide.common.async;
import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class AsyncTaskExecutionServiceTest {
    @Test void handlerFailureRecordsRetryAndDoesNotComplete() {
        var state = mock(AsyncTaskStateService.class);
        var handler = mock(AsyncTaskHandler.class);
        when(handler.type()).thenReturn(AsyncTaskType.RESUME_ANALYZE);
        var claim = new AsyncTaskStateService.Claim(UUID.randomUUID(), 1L, AsyncTaskType.RESUME_ANALYZE, "1", 1, Map.of());
        when(state.claim(any())).thenReturn(claim);
        when(handler.isCurrent(claim.id(), claim.businessId())).thenReturn(true);
        doThrow(new RuntimeException("provider unavailable")).when(handler).execute(any());
        var service = new AsyncTaskExecutionService(state, new AsyncTaskHandlerRegistry(List.of(handler)), new KafkaTaskProperties());
        try { service.execute(new TaskEnvelope(1, UUID.randomUUID(), claim.id(), claim.type(), "1", Instant.now())); }
        finally { service.shutdown(); }
        verify(state).fail(claim, true);
        verify(state, never()).complete(any(), any());
    }
    @Test void timeoutInterruptsWorkAndSchedulesRecovery() {
        var state = mock(AsyncTaskStateService.class);
        var handler = mock(AsyncTaskHandler.class);
        when(handler.type()).thenReturn(AsyncTaskType.RESUME_ANALYZE);
        var claim = new AsyncTaskStateService.Claim(UUID.randomUUID(), 1L, AsyncTaskType.RESUME_ANALYZE, "1", 1, Map.of());
        when(state.claim(any())).thenReturn(claim);
        when(handler.isCurrent(claim.id(), claim.businessId())).thenReturn(true);
        doAnswer(inv -> { Thread.sleep(5000); return null; }).when(handler).execute(any());
        var properties = new KafkaTaskProperties(); properties.setExecutionTimeout(Duration.ofMillis(50));
        var service = new AsyncTaskExecutionService(state, new AsyncTaskHandlerRegistry(List.of(handler)), properties);
        try { service.execute(new TaskEnvelope(1, UUID.randomUUID(), claim.id(), claim.type(), "1", Instant.now())); }
        finally { service.shutdown(); }
        verify(state).fail(claim, true);
    }
    @Test void failurePersistenceErrorIsNotSwallowed() {
        var state = mock(AsyncTaskStateService.class);
        var handler = mock(AsyncTaskHandler.class);
        when(handler.type()).thenReturn(AsyncTaskType.RESUME_ANALYZE);
        var claim = new AsyncTaskStateService.Claim(UUID.randomUUID(), 1L, AsyncTaskType.RESUME_ANALYZE, "1", 1, Map.of());
        when(state.claim(any())).thenReturn(claim);
        when(handler.isCurrent(claim.id(), claim.businessId())).thenReturn(true);
        doThrow(new IllegalArgumentException()).when(handler).execute(any());
        doThrow(new IllegalStateException("database down")).when(state).fail(claim, false);
        var service = new AsyncTaskExecutionService(state, new AsyncTaskHandlerRegistry(List.of(handler)), new KafkaTaskProperties());
        try { assertThatThrownBy(() -> service.execute(null)).hasMessage("database down"); }
        finally { service.shutdown(); }
    }
}
