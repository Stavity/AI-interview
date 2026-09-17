package interview.guide.modules.voiceinterview.listener;
import interview.guide.common.async.*;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.task.VoiceEvaluateTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
@Service @RequiredArgsConstructor
public class VoiceEvaluateTaskSubmission {
    private final AsyncTaskSubmissionService tasks;
    private final VoiceInterviewEvaluationService evaluation;
    private final VoiceEvaluateTaskHandler handler;
    private final ObjectMapper mapper;
    @Transactional
    public void sendEvaluateTask(String id) {
        tasks.submit(AsyncTaskType.VOICE_EVALUATE, id,
            Map.of("snapshot", mapper.writeValueAsString(evaluation.snapshot(Long.valueOf(id)))));
        handler.updateStatus(id, AsyncTaskState.QUEUED, null);
    }
}
