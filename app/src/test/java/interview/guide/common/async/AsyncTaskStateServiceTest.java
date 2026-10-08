package interview.guide.common.async;

import interview.guide.infrastructure.async.*;
import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AsyncTaskStateServiceTest {
    @Mock AsyncTaskRepository tasks;
    @Mock TaskOutboxRepository outboxRows;
    @Mock TaskOutboxWriter outbox;
    @Mock AsyncTaskHandler handler;
    AsyncTaskStateService service;
    AsyncTaskEntity task;
    TaskEnvelope event;

    @BeforeEach void setUp() {
        when(handler.type()).thenReturn(AsyncTaskType.RESUME_ANALYZE);
        service = new AsyncTaskStateService(tasks, outboxRows, outbox,
            new AsyncTaskHandlerRegistry(List.of(handler)), new KafkaTaskProperties(), new ObjectMapper());
        task = new AsyncTaskEntity(); task.setId(UUID.randomUUID()); task.setTaskType(AsyncTaskType.RESUME_ANALYZE);
        task.setBusinessId("42"); task.setState(AsyncTaskState.QUEUED); task.setNextRunAt(Instant.now().minusSeconds(1));
        task.setInputPayload("{\"content\":\"snapshot\"}"); task.setMaxAttempts(4); task.setActiveKey("RESUME_ANALYZE:42");
        event = new TaskEnvelope(1, UUID.randomUUID(), task.getId(), task.getTaskType(), "42", Instant.now());
        lenient().when(tasks.lock(task.getId())).thenReturn(Optional.of(task));
        lenient().when(handler.isCurrent(task.getId(), "42")).thenReturn(true);
    }

    @Test void claimsOnlyOnceAndRetainsSnapshot() {
        var claim = service.claim(event);
        assertThat(claim.input()).containsEntry("content", "snapshot");
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(service.claim(event)).isNull();
        verify(handler, never()).updateStatus(any(), any(), any());
    }
    @Test void rejectsMismatchedEnvelope() {
        assertThatThrownBy(() -> service.claim(new TaskEnvelope(1, event.eventId(), event.taskId(),
            AsyncTaskType.RESUME_ANALYZE, "99", Instant.now()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void deletedBusinessCancelsBeforeResultPersistence() {
        var claim = service.claim(event);
        when(handler.isCurrent(task.getId(), "42")).thenReturn(false);
        var write = mock(Runnable.class);
        service.complete(claim, write);
        assertThat(task.getState()).isEqualTo(AsyncTaskState.CANCELLED);
        assertThat(task.getActiveKey()).isNull();
        verifyNoInteractions(write);
    }
    @Test void completionPreventsReplay() {
        var claim = service.claim(event); var write = mock(Runnable.class);
        service.complete(claim, write);
        assertThat(task.getState()).isEqualTo(AsyncTaskState.SUCCEEDED);
        assertThat(task.getActiveKey()).isNull();
        assertThat(service.claim(event)).isNull();
        verify(write).run();
    }
    @Test void staleExecutionCannotPersistOrFailCurrentTask() {
        var claim = service.claim(event); task.setExecutionVersion(claim.version() + 1);
        var write = mock(Runnable.class);
        assertThatThrownBy(() -> service.complete(claim, write))
            .isInstanceOf(AsyncTaskStateService.StaleTaskExecutionException.class);
        service.fail(claim, true);
        verifyNoInteractions(write, outbox);
        assertThat(task.getState()).isEqualTo(AsyncTaskState.RUNNING);
    }
    @Test void expiredLeaseCannotBeRevivedOrComplete() {
        var claim = service.claim(event); task.setLeaseUntil(Instant.now().minusSeconds(1));
        assertThat(service.heartbeat(claim)).isFalse();
        assertThatThrownBy(() -> service.complete(claim, () -> fail("Must not write")))
            .isInstanceOf(AsyncTaskStateService.StaleTaskExecutionException.class);
    }
    @Test void schedulesRetryBeforeAcknowledging() {
        var claim = service.claim(event); service.fail(claim, true);
        assertThat(task.getState()).isEqualTo(AsyncTaskState.RETRY_WAIT);
        assertThat(task.getNextRunAt()).isAfter(Instant.now().plusSeconds(20));
        verify(outbox).enqueue(task, task.getNextRunAt(), false);
        assertThat(service.claim(event)).isNull();
    }
    @Test void retryExhaustionCreatesDeadLetterAndReleasesActiveKey() {
        var claim = service.claim(event); task.setAttemptCount(4); service.fail(claim, true);
        assertThat(task.getState()).isEqualTo(AsyncTaskState.FAILED);
        assertThat(task.getActiveKey()).isNull();
        verify(outbox).enqueue(eq(task), any(), eq(true));
    }
    @Test void invalidInputDoesNotRetry() {
        var claim = service.claim(event); service.fail(claim, false);
        assertThat(task.getState()).isEqualTo(AsyncTaskState.FAILED);
    }
    @Test void recoveryFencesExpiredWorker() {
        var claim = service.claim(event);
        when(tasks.lockRecoverable()).thenReturn(List.of(task));
        service.recover();
        assertThat(task.getExecutionVersion()).isGreaterThan(claim.version());
        assertThat(task.getState()).isEqualTo(AsyncTaskState.RETRY_WAIT);
        assertThatThrownBy(() -> service.complete(claim, () -> fail("Must not write")))
            .isInstanceOf(AsyncTaskStateService.StaleTaskExecutionException.class);
    }
    @Test void infrastructureFailurePropagatesInsteadOfAcknowledging() {
        when(tasks.lock(task.getId())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.claim(event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
