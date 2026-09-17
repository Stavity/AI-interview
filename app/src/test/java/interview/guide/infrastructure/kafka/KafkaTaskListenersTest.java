package interview.guide.infrastructure.kafka;
import interview.guide.common.async.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class KafkaTaskListenersTest {
    @Test void invalidJsonNeverReachesBusinessExecution() {
        var executor = mock(AsyncTaskExecutionService.class);
        var listeners = new KafkaTaskListeners(executor, new ObjectMapper());
        assertThatThrownBy(() -> listeners.resume("invalid")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(executor);
    }
    @Test void wrongTopicAndUnknownSchemaAreRejected() {
        var executor = mock(AsyncTaskExecutionService.class);
        var mapper = new ObjectMapper(); var listeners = new KafkaTaskListeners(executor, mapper);
        var event = new TaskEnvelope(1, UUID.randomUUID(), UUID.randomUUID(), AsyncTaskType.RESUME_ANALYZE, "1", Instant.now());
        assertThatThrownBy(() -> listeners.voice(mapper.writeValueAsString(event))).isInstanceOf(IllegalArgumentException.class);
        var unknown = new TaskEnvelope(2, event.eventId(), event.taskId(), event.taskType(), "1", Instant.now());
        assertThatThrownBy(() -> listeners.resume(mapper.writeValueAsString(unknown))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(executor);
    }
}
