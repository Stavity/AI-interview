package interview.guide.modules.resume.listener;
import interview.guide.common.async.*;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service @RequiredArgsConstructor
public class AnalyzeTaskSubmission {
    private final AsyncTaskSubmissionService tasks;
    private final ResumeRepository repository;
    @Transactional
    public void sendAnalyzeTask(Long id, String content) {
        var row = repository.findById(id).orElseThrow();
        tasks.submit(AsyncTaskType.RESUME_ANALYZE, id.toString(), Map.of("content", content));
        row.setAnalyzeStatus(interview.guide.common.model.AsyncTaskStatus.PENDING); row.setAnalyzeError(null); repository.save(row);
    }
}
