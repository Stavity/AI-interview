package interview.guide.modules.voiceinterview.task;
import interview.guide.common.async.*;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component @RequiredArgsConstructor
public class VoiceEvaluateTaskHandler implements AsyncTaskHandler {
    private final VoiceInterviewSessionRepository repository;
    private final VoiceInterviewEvaluationService evaluation;
    private final ObjectMapper mapper;
    public AsyncTaskType type() { return AsyncTaskType.VOICE_EVALUATE; }
    public boolean exists(String id) { return repository.existsById(Long.valueOf(id)); }
    public void execute(TaskExecutionContext context) {
        var input = mapper.readValue(context.required("snapshot"), VoiceInterviewEvaluationService.EvaluationInput.class);
        evaluation.generateEvaluation(
            Long.valueOf(context.businessId()), input, context.taskId(),
            context.executionVersion(), context::complete);
    }
    public void updateStatus(String id, AsyncTaskState state, String error) {
        repository.findById(Long.valueOf(id)).ifPresent(row -> {
            row.setEvaluateStatus(TaskStatusMapping.legacy(state)); row.setEvaluateError(error); repository.save(row);
        });
    }
}
