package interview.guide.modules.interview.listener;
import interview.guide.common.async.*;
import interview.guide.modules.interview.task.InterviewEvaluateTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class EvaluateTaskSubmission {
    private final AsyncTaskSubmissionService tasks;
    private final InterviewEvaluateTaskHandler handler;
    @Transactional
    public void sendEvaluateTask(String id) {
        tasks.submit(AsyncTaskType.INTERVIEW_EVALUATE, id, handler.snapshot(id));
        handler.updateStatus(id, AsyncTaskState.QUEUED, null);
    }
}
