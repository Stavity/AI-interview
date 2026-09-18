package interview.guide.modules.knowledgebase.listener;
import interview.guide.common.async.*;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service @RequiredArgsConstructor
public class VectorizeTaskSubmission {
    private final AsyncTaskSubmissionService tasks;
    private final KnowledgeBaseRepository repository;
    @Transactional
    public void sendVectorizeTask(Long id) {
        var row = repository.findById(id).orElseThrow();
        // 解析文本已持久化到 knowledge_bases.parsed_content，任务只投递 businessId，
        // 由 handler 从 KB 行读取，避免把整段内容塞进 async_task.input_payload JSONB
        tasks.submit(AsyncTaskType.KB_VECTORIZE, id.toString(), Map.of());
        row.setVectorStatus(interview.guide.modules.knowledgebase.model.VectorStatus.PENDING); row.setVectorError(null); repository.save(row);
    }
}
