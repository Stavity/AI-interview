package interview.guide.infrastructure.kafka;
import interview.guide.infrastructure.async.*;
import interview.guide.common.transaction.TransactionalExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class TaskOutboxDispatcherTest {
    @Mock TaskOutboxRepository repository;
    @Mock TransactionalExecutor tx;
    @Mock KafkaTemplate<String, String> kafka;
    TaskOutboxDispatcher dispatcher;
    TaskOutboxEntity row;
    @BeforeEach void setup() {
        when(tx.call(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(tx).run(any());
        row = new TaskOutboxEntity(); row.setId(UUID.randomUUID()); row.setTopic("topic"); row.setMessageKey("key"); row.setPayload("event");
        when(repository.lockReady()).thenReturn(List.of(row));
        dispatcher = new TaskOutboxDispatcher(repository, tx, kafka, new KafkaTaskProperties());
    }
    @Test void brokerConfirmationPrecedesMarkingSent() {
        when(kafka.send("topic", "key", "event")).thenReturn(CompletableFuture.completedFuture(null));
        dispatcher.dispatch();
        var order = inOrder(kafka, repository);
        order.verify(kafka).send("topic", "key", "event");
        order.verify(repository).markSent(eq(row.getId()), eq(row.getLeaseOwner()), any());
        assertThat(row.getPublishAttempts()).isEqualTo(1);
    }
    @Test void brokerFailureKeepsNotificationForRetry() {
        when(kafka.send("topic", "key", "event")).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        dispatcher.dispatch();
        verify(repository, never()).markSent(any(), any(), any());
        verify(repository).release(eq(row.getId()), eq(row.getLeaseOwner()), argThat(next -> next.isAfter(Instant.now())));
    }
}
