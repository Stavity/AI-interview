package interview.guide.common.ai.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

class GoogleAdkRunnerContractTest {
  @Test
  void shouldRunAgentAgainstSessionCreatedByFactory() {
    AdkSessionFactory factory = new AdkSessionFactory();
    AdkSessionFactory.SessionHandle session = factory.create(12L, 34L, 1, "b".repeat(64),
        "interview-planning-v1");
    LlmAgent agent = LlmAgent.builder()
        .name("contract_agent")
        .model(new FixedResponseModel())
        .instruction("Return the fixed response.")
        .maxSteps(1)
        .build();
    Runner runner = Runner.builder().agent(agent).appName("ai-interview")
        .sessionService(factory.sessionService()).build();

    String output = runner.runAsync("local-single-user", session.sessionId(),
        Content.builder().role("user").parts(Part.fromText("plan current round")).build())
        .blockingLast().stringifyContent();

    assertThat(output).contains("ALGORITHM");
  }

  private static final class FixedResponseModel extends BaseLlm {
    private FixedResponseModel() { super("fake-model"); }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
      Content content = Content.builder().role("model")
          .parts(Part.fromText("{\"roundNo\":1,\"roundType\":\"ALGORITHM\"}"))
          .build();
      return Flowable.just(LlmResponse.builder().content(content).build());
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
      throw new UnsupportedOperationException();
    }
  }
}
