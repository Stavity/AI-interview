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
        Long kbId = Long.valueOf(context.businessId());
        // 从 knowledge_bases.parsed_content 读取解析文本，不再从任务载荷取
        String content = repository.findById(kbId)
            .map(interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity::getParsedContent)
            .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + context.businessId()));
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("parsed_content 为空，无法向量化: kbId=" + context.businessId());
        }
        vectors.vectorizeAndStore(kbId, content,
            context.taskId() + ":" + context.executionVersion(), context::complete);
    }
    public void updateStatus(String id, AsyncTaskState state, String error) {
        repository.findById(Long.valueOf(id)).ifPresent(row -> {
            row.setVectorStatus(VectorStatus.valueOf(TaskStatusMapping.legacy(state).name())); row.setVectorError(error); repository.save(row);
        });
    }
}
