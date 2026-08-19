package interview.guide.modules.multiround;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.multiround.service.AgentRunTraceService;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MultiRoundControllerTest {
  @Test
  void shouldExposeAgentStepEventTypeInTraceResponse() {
    var response = MultiRoundController.AgentTraceStepResponse.from(
        new AgentRunTraceService.StepTrace(1, "TOOL_CALL", "read_prior_answers", "SUCCEEDED",
            "answers=2", 5L, LocalDateTime.now()));

    assertThat(response.eventType()).isEqualTo("TOOL_CALL");
  }

  @Test
  void shouldExposeReadOnlyAgentRuntimeConfiguration() {
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setRuntime(AgentRuntimeProperties.Runtime.SHADOW);
    properties.setPolicyVersion("policy-v2");

    var response = MultiRoundController.AgentRuntimeResponse.from(properties);

    assertThat(response.runtime()).isEqualTo("SHADOW");
    assertThat(response.policyVersion()).isEqualTo("policy-v2");
  }
}
