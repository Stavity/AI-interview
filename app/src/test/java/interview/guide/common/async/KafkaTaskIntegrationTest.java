package interview.guide.common.async;

import interview.guide.infrastructure.async.*;
import interview.guide.infrastructure.kafka.*;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.common.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Uses the real Flyway SQL, PostgreSQL row locks, Kafka publication and record acknowledgments. */
@Testcontainers(disabledWithoutDocker = true)
class KafkaTaskIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.1");
    static AnnotationConfigApplicationContext context;
    static KafkaTemplate<String, String> template;
    static DefaultKafkaProducerFactory<String, String> producerFactory;
    static final AtomicInteger deliveries = new AtomicInteger();
    static KafkaMessageListenerContainer<String, String> container;
    static JdbcTemplate jdbc;
    @BeforeAll static void open() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table task_result (task_id uuid primary key)");
        var admin = new KafkaAdmin(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
        admin.createOrModifyTopics(org.springframework.kafka.config.TopicBuilder.name(AsyncTaskType.RESUME_ANALYZE.topic()).partitions(1).replicas(1).build());
        producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all"));
        template = new KafkaTemplate<>(producerFactory);
    }
    @AfterAll static void close() {
        if (container != null) container.stop();
        if (producerFactory != null) producerFactory.destroy();
        if (context != null) context.close();
    }
    @BeforeEach void clear() {
        if (container != null) { container.stop(); container = null; }
        context.getBean(TaskOutboxRepository.class).deleteAll(); context.getBean(AsyncTaskRepository.class).deleteAll();
        jdbc.update("delete from task_result"); deliveries.set(0);
    }
    @Test void outboxToKafkaToAtomicResultAndDuplicateAcknowledgment() throws Exception {
        var submission = context.getBean(AsyncTaskSubmissionService.class);
        var tasks = context.getBean(AsyncTaskRepository.class);
        var rows = context.getBean(TaskOutboxRepository.class);
        UUID id = TenantContext.callAs(1L,
            () -> submission.submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of("content", "private snapshot")));
        var message = rows.findAll().getFirst();
        var consumerFactory = new DefaultKafkaConsumerFactory<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        var properties = new ContainerProperties(AsyncTaskType.RESUME_ANALYZE.topic());
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        var listeners = new KafkaTaskListeners(context.getBean(AsyncTaskExecutionService.class), context.getBean(tools.jackson.databind.ObjectMapper.class));
        properties.setMessageListener((MessageListener<String, String>) record -> {
            listeners.consume(record.value(), AsyncTaskType.RESUME_ANALYZE); deliveries.incrementAndGet();
        });
        container = new KafkaMessageListenerContainer<>(consumerFactory, properties); container.start();
        var dispatcher = new TaskOutboxDispatcher(rows, context.getBean(TransactionalExecutor.class), template, new KafkaTaskProperties());
        dispatcher.dispatch();
        await(() -> tasks.findById(id).orElseThrow().getState() == AsyncTaskState.SUCCEEDED);
        template.send(message.getTopic(), message.getMessageKey(), message.getPayload()).get(10, TimeUnit.SECONDS);
        await(() -> deliveries.get() >= 2);
        assertThat(jdbc.queryForObject("select count(*) from task_result", Integer.class)).isEqualTo(1);
        assertThat(rows.findById(message.getId()).orElseThrow().getState()).isEqualTo("SENT");
    }
    @Test void postgresRecoveryFencesOldWorkerAndCreatesDurableRetry() {
        var tasks = context.getBean(AsyncTaskRepository.class);
        var state = context.getBean(AsyncTaskStateService.class);
        var id = TenantContext.callAs(1L, () -> context.getBean(AsyncTaskSubmissionService.class)
            .submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of()));
        var claim = state.claim(AsyncTaskPersistenceTest.event(id));
        jdbc.update("update async_task set lease_until = CURRENT_TIMESTAMP - INTERVAL '5 minutes' where id = ?", id);
        state.recover();
        assertThat(tasks.findById(id).orElseThrow().getState()).isEqualTo(AsyncTaskState.RETRY_WAIT);
        assertThat(context.getBean(TaskOutboxRepository.class).count()).isEqualTo(2);
        assertThatThrownBy(() -> state.complete(claim, () -> fail("stale write")))
            .isInstanceOf(AsyncTaskStateService.StaleTaskExecutionException.class);
    }
    @Test void concurrentClaimsOnlyStartOneWorker() throws Exception {
        var state = context.getBean(AsyncTaskStateService.class);
        var id = TenantContext.callAs(1L, () -> context.getBean(AsyncTaskSubmissionService.class)
            .submit(AsyncTaskType.RESUME_ANALYZE, "1", Map.of()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = executor.invokeAll(List.of(
                () -> state.claim(AsyncTaskPersistenceTest.event(id)),
                () -> state.claim(AsyncTaskPersistenceTest.event(id))));
            int claimed = 0;
            for (var future : futures) if (future.get() != null) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
    }
    static void await(java.util.function.BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!check.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(check.getAsBoolean()).isTrue();
    }
    @Configuration @Import(AsyncTaskExecutionService.class)
    static class Config extends AsyncTaskPersistenceTest.Config {
        @Override @Bean DataSource dataSource() {
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20260911__add_async_task_and_outbox.sql")).execute(ds);
            new JdbcTemplate(ds).execute("alter table async_task add column owner_user_id bigint not null");
            return ds;
        }
        @Override @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.getJpaPropertyMap().put("hibernate.hbm2ddl.auto", "validate");
            return factory;
        }
        @Override @Bean AsyncTaskHandler handler() {
            return new AsyncTaskHandler() {
                public AsyncTaskType type() { return AsyncTaskType.RESUME_ANALYZE; }
                public boolean exists(String id) { return true; }
                public void updateStatus(String id, AsyncTaskState state, String error) {}
                public void execute(TaskExecutionContext ctx) {
                    ctx.complete(() -> jdbc.update("insert into task_result(task_id) values (?)", ctx.taskId()));
                }
            };
        }
    }
}
