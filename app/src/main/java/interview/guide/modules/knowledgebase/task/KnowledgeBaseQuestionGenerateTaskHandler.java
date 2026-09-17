package interview.guide.modules.knowledgebase.task;
import interview.guide.common.async.*;
import interview.guide.modules.knowledgebase.model.*;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component @RequiredArgsConstructor
public class KnowledgeBaseQuestionGenerateTaskHandler implements AsyncTaskHandler {
    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseQuestionGenerationService generation;
    private final ObjectMapper mapper;
    public AsyncTaskType type() { return AsyncTaskType.QUESTION_GENERATE; }
    public boolean exists(String id) { return repository.existsById(Long.valueOf(id)); }
    public boolean isCurrent(java.util.UUID taskId, String id) {
        return repository.findById(Long.valueOf(id))
            .map(row -> taskId.toString().equals(row.getQuestionGenTaskId())).orElse(false);
    }
    public void execute(TaskExecutionContext context) {
        var config = mapper.readValue(context.required("config"), QuestionGenerationConfig.class);
        generation.executeGeneration(Long.valueOf(context.businessId()), context.taskId().toString(), config, context::complete);
    }
    public void updateStatus(String id, AsyncTaskState state, String error) {
        repository.findById(Long.valueOf(id)).ifPresent(row -> {
            row.setQuestionGenStatus(switch (state) {
                case RUNNING -> QuestionGenStatus.PROCESSING;
                case SUCCEEDED -> QuestionGenStatus.COMPLETED;
                case FAILED, CANCELLED -> QuestionGenStatus.FAILED;
                default -> QuestionGenStatus.QUEUED;
            });
            row.setQuestionGenError(error); row.setQuestionGenUpdatedAt(java.time.LocalDateTime.now()); repository.save(row);
        });
    }
}
