package interview.guide.modules.voiceinterview.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.async.TaskExecutionContext;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VoiceEvaluateTaskHandlerTest {

  @Test
  void forwardsTaskIdentityAndExecutionVersionToEvidenceWritePath() {
    VoiceInterviewSessionRepository repository = mock(VoiceInterviewSessionRepository.class);
    VoiceInterviewEvaluationService evaluation = mock(VoiceInterviewEvaluationService.class);
    ObjectMapper mapper = mock(ObjectMapper.class);
    VoiceEvaluateTaskHandler handler = new VoiceEvaluateTaskHandler(repository, evaluation, mapper);
    VoiceInterviewEvaluationService.EvaluationInput input =
        new VoiceInterviewEvaluationService.EvaluationInput(
            List.of(), "dashscope", "", "Java", null, List.of());
    when(mapper.readValue("snapshot-json", VoiceInterviewEvaluationService.EvaluationInput.class))
        .thenReturn(input);
    UUID taskId = UUID.randomUUID();
    TaskExecutionContext context = new TaskExecutionContext(
        taskId, 7L, "9", 4L, Map.of("snapshot", "snapshot-json"), Runnable::run);

    handler.execute(context);

    verify(evaluation).generateEvaluation(
        eq(9L), eq(input), eq(taskId), eq(4L), any());
  }
}
