package interview.guide.common.async;

import interview.guide.infrastructure.async.*;
import interview.guide.infrastructure.kafka.KafkaTaskProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests actual transaction boundaries and uniqueness; PostgreSQL-only queries have separate tests. */
class AsyncTaskPersistenceTest {
    static AnnotationConfigApplicationContext context;
    AsyncTaskSubmissionService submission;
    AsyncTaskStateService state;
    AsyncTaskRepository tasks;
    TaskOutboxRepository outbox;
    TransactionalExecutor tx;
    @BeforeAll static void open() { context = new AnnotationConfigApplicationContext(Config.class); }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void prepare() {
        submission = context.getBean(AsyncTaskSubmissionService.class); state = context.getBean(AsyncTaskStateService.class);
        tasks = context.getBean(AsyncTaskRepository.class); outbox = context.getBean(TaskOutboxRepository.class);
        tx = context.getBean(TransactionalExecutor.class);
        outbox.deleteAll(); tasks.deleteAll();
    }
    @Test void businessRollbackAlsoRollsBackTaskAndOutbox() {
        assertThatThrownBy(() -> tx.run(() -> {
            submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of("content", "input"));
            throw new IllegalStateException("business rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.count()).isZero(); assertThat(outbox.count()).isZero();
    }
    @Test void committedTaskContainsSnapshotAndOnlyReferenceIsPublished() {
        var id = submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of("content", "private text"));
        assertThat(tasks.findById(id).orElseThrow().getInputPayload()).contains("private text");
        assertThat(outbox.findAll().getFirst().getPayload()).contains(id.toString()).doesNotContain("private text");
        assertThat(state.claim(event(id)).input()).containsEntry("content", "private text");
    }
    @Test void activeTaskCannotBeSubmittedAgain() {
        submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of());
        assertThatThrownBy(() -> submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of()))
            .isInstanceOf(interview.guide.common.exception.BusinessException.class);
        assertThat(tasks.count()).isEqualTo(1); assertThat(outbox.count()).isEqualTo(1);
    }
    @Test void resultFailureRollsBackCompletionAndAllowsRecovery() {
        var id = submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of());
        var claim = state.claim(event(id));
        assertThatThrownBy(() -> state.complete(claim, () -> {
            // Simulate result writes using a row in the same database, then fail.
            submission.submit(AsyncTaskType.RESUME_ANALYZE, "result-row", Map.of());
            throw new IllegalStateException("result persistence failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.count()).isEqualTo(1);
        assertThat(tasks.findById(id).orElseThrow().getState()).isEqualTo(AsyncTaskState.RUNNING);
    }
    @Test void successAndDuplicateDeliveryOnlyCommitOnce() {
        var id = submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of());
        var claim = state.claim(event(id));
        state.complete(claim, () -> submission.submit(AsyncTaskType.RESUME_ANALYZE, "result-row", Map.of()));
        assertThat(state.claim(event(id))).isNull();
        assertThat(tasks.count()).isEqualTo(2);
        assertThat(tasks.findById(id).orElseThrow().getState()).isEqualTo(AsyncTaskState.SUCCEEDED);
        // A new user-requested generation can now use the same business key.
        assertThat(submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of())).isNotEqualTo(id);
    }
    @Test void retryStateAndNotificationCommitTogether() {
        var id = submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of());
        state.fail(state.claim(event(id)), true);
        assertThat(tasks.findById(id).orElseThrow().getState()).isEqualTo(AsyncTaskState.RETRY_WAIT);
        assertThat(outbox.count()).isEqualTo(2);
    }
    static TaskEnvelope event(UUID id) { return new TaskEnvelope(1, UUID.randomUUID(), id, AsyncTaskType.RESUME_ANALYZE, "1", Instant.now()); }

    @Configuration @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AsyncTaskRepository.class)
    @Import({AsyncTaskSubmissionService.class, AsyncTaskStateService.class, AsyncTaskHandlerRegistry.class,
        TaskOutboxWriter.class, TransactionalExecutor.class})
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:tasks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(dataSource);
            factory.setPackagesToScan(AsyncTaskEntity.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean KafkaTaskProperties properties() { return new KafkaTaskProperties(); }
        @Bean AsyncTaskHandler handler() {
            var handler = mock(AsyncTaskHandler.class); when(handler.type()).thenReturn(AsyncTaskType.RESUME_ANALYZE);
            when(handler.isCurrent(any(), any())).thenReturn(true); return handler;
        }
    }
}
