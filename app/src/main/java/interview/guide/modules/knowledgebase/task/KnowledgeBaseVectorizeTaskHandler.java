package interview.guide.modules.knowledgebase.task;
import interview.guide.common.async.*;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class KnowledgeBaseVectorizeTaskHandler implements AsyncTaskHandler {
    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseVectorService vectors;
    public AsyncTaskType type() { return AsyncTaskType.KB_VECTORIZE; }
    public boolean exists(String id) { return repository.existsById(Long.valueOf(id)); }
    public void execute(TaskExecutionContext context) {
        vectors.vectorizeAndStore(Long.valueOf(context.businessId()), context.required("content"),
            context.taskId() + ":" + context.executionVersion(), context::complete);
    }
    public void updateStatus(String id, AsyncTaskState state, String error) {
        repository.findById(Long.valueOf(id)).ifPresent(row -> {
            row.setVectorStatus(VectorStatus.valueOf(TaskStatusMapping.legacy(state).name())); row.setVectorError(error); repository.save(row);
        });
    }
}
