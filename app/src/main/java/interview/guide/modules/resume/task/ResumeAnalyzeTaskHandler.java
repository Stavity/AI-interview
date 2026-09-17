package interview.guide.modules.resume.task;
import interview.guide.common.async.*;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class ResumeAnalyzeTaskHandler implements AsyncTaskHandler {
    private final ResumeRepository repository;
    private final ResumeGradingService grading;
    private final ResumePersistenceService persistence;
    public AsyncTaskType type() { return AsyncTaskType.RESUME_ANALYZE; }
    public boolean exists(String id) { return repository.existsById(Long.valueOf(id)); }
    public void execute(TaskExecutionContext context) {
        var result = grading.analyzeResume(context.required("content"));
        context.complete(() -> persistence.saveAnalysis(repository.findById(Long.valueOf(context.businessId())).orElseThrow(), result));
    }
    public void updateStatus(String id, AsyncTaskState state, String error) {
        repository.findById(Long.valueOf(id)).ifPresent(row -> {
            row.setAnalyzeStatus(TaskStatusMapping.legacy(state)); row.setAnalyzeError(error); repository.save(row);
        });
    }
}
