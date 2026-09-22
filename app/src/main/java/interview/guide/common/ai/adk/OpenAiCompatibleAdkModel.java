package interview.guide.common.ai.adk;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;

/** ADK model bridge for the project's provider registry OpenAI-compatible endpoints. */
final class OpenAiCompatibleAdkModel extends BaseLlm {
  private final ChatCompletionsHttpClient client;

  OpenAiCompatibleAdkModel(String model, String baseUrl, String apiKey, long timeoutMs) {
    super(model);
    if (timeoutMs < 1 || timeoutMs > Integer.MAX_VALUE) throw new IllegalArgumentException("ADK timeout 非法");
    client = new ChatCompletionsHttpClient(HttpOptions.builder().baseUrl(baseUrl)
        .headers(Map.of("Authorization", "Bearer " + apiKey)).timeout((int) timeoutMs).build());
  }

  @Override public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
    return client.complete(request, stream);
  }
  @Override public BaseLlmConnection connect(LlmRequest request) {
    throw new UnsupportedOperationException("多轮规划不使用 ADK realtime connection");
  }
}
