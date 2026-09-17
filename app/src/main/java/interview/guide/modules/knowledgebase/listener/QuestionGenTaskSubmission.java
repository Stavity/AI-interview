package interview.guide.modules.knowledgebase.listener;
import interview.guide.common.async.*;
import interview.guide.modules.knowledgebase.service.QuestionGenerationStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class QuestionGenTaskSubmission {
    private final AsyncTaskSubmissionService tasks;
    private final QuestionGenerationStateService state;
    private final ObjectMapper mapper;
    @Transactional
    public boolean sendGenerateTask(Long id, String taskId) {
        tasks.submit(UUID.fromString(taskId), AsyncTaskType.QUESTION_GENERATE, id.toString(),
            Map.of("config", mapper.writeValueAsString(state.getConfig(id, taskId))));
        return true;
    }
}
