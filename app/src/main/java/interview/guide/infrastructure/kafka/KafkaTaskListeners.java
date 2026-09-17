package interview.guide.infrastructure.kafka;
import interview.guide.common.async.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component @RequiredArgsConstructor
public class KafkaTaskListeners {
    private final AsyncTaskExecutionService executor;
    private final ObjectMapper mapper;
    public void consume(String raw, AsyncTaskType expected) {
        TaskEnvelope event;
        try { event = mapper.readValue(raw, TaskEnvelope.class); event.validate(); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid task notification"); }
        if (event.taskType() != expected) throw new IllegalArgumentException("Task delivered to incorrect topic");
        executor.execute(event);
    }
    @KafkaListener(topics = "ai-interview.resume-analyze.v1", groupId = "ai-interview.resume-analyze.worker.v1",
        autoStartup = "${app.tasks.consumer-enabled:true}", concurrency = "${app.tasks.concurrency.resume-analyze:1}")
    public void resume(String message) { consume(message, AsyncTaskType.RESUME_ANALYZE); }
    @KafkaListener(topics = "ai-interview.kb-vectorize.v1", groupId = "ai-interview.kb-vectorize.worker.v1",
        autoStartup = "${app.tasks.consumer-enabled:true}", concurrency = "${app.tasks.concurrency.kb-vectorize:1}")
    public void vectorize(String message) { consume(message, AsyncTaskType.KB_VECTORIZE); }
    @KafkaListener(topics = "ai-interview.interview-evaluate.v1", groupId = "ai-interview.interview-evaluate.worker.v1",
        autoStartup = "${app.tasks.consumer-enabled:true}", concurrency = "${app.tasks.concurrency.interview-evaluate:1}")
    public void interview(String message) { consume(message, AsyncTaskType.INTERVIEW_EVALUATE); }
    @KafkaListener(topics = "ai-interview.voice-evaluate.v1", groupId = "ai-interview.voice-evaluate.worker.v1",
        autoStartup = "${app.tasks.consumer-enabled:true}", concurrency = "${app.tasks.concurrency.voice-evaluate:1}")
    public void voice(String message) { consume(message, AsyncTaskType.VOICE_EVALUATE); }
    @KafkaListener(topics = "ai-interview.question-generate.v1", groupId = "ai-interview.question-generate.worker.v1",
        autoStartup = "${app.tasks.consumer-enabled:true}", concurrency = "${app.tasks.concurrency.question-generate:1}")
    public void questions(String message) { consume(message, AsyncTaskType.QUESTION_GENERATE); }
}
