package interview.guide.infrastructure.kafka;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import interview.guide.common.async.AsyncTaskType;
import java.util.*;
@Configuration
public class KafkaTaskConfiguration {
    @Bean
    public KafkaAdmin.NewTopics taskTopics(KafkaTaskProperties properties) {
        List<NewTopic> topics = new ArrayList<>();
        for (var type : AsyncTaskType.values())
            topics.add(TopicBuilder.name(type.topic()).partitions(properties.getPartitions()).replicas(properties.getReplicas())
                .config("min.insync.replicas", properties.getReplicas() >= 3 ? "2" : "1").build());
        topics.add(TopicBuilder.name(KafkaTaskProperties.DLT).partitions(properties.getPartitions()).replicas(properties.getReplicas())
                .config("min.insync.replicas", properties.getReplicas() >= 3 ? "2" : "1").build());
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
    @Bean
    public CommonErrorHandler taskErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
            (record, exception) -> new TopicPartition(KafkaTaskProperties.DLT, -1));
        recoverer.setFailIfSendResultIsError(true);
        // Infrastructure failures must not silently exhaust retries and discard work.
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
