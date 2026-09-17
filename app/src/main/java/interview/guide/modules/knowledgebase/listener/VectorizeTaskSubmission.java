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
    public void sendVectorizeTask(Long id, String content) {
        var row = repository.findById(id).orElseThrow();
        tasks.submit(AsyncTaskType.KB_VECTORIZE, id.toString(), Map.of("content", content));
        row.setVectorStatus(interview.guide.modules.knowledgebase.model.VectorStatus.PENDING); row.setVectorError(null); repository.save(row);
    }
}
