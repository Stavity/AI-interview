package interview.guide.common.ai.adk;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;

/** Bridges ADK's model contract to providers exposing OpenAI-compatible chat completions. */
final class OpenAiCompatibleAdkModel extends BaseLlm {
  private final ChatCompletionsHttpClient client;

  OpenAiCompatibleAdkModel(String model, String baseUrl, String apiKey, long timeoutMs) {
    super(model);
    this.client = new ChatCompletionsHttpClient(HttpOptions.builder()
        .baseUrl(baseUrl)
        .headers(Map.of("Authorization", "Bearer " + apiKey))
        .timeout(toTimeoutMillis(timeoutMs))
        .build());
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
    return client.complete(request, stream);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request) {
    throw new UnsupportedOperationException("多轮面试规划不支持 ADK 实时连接");
  }

  private int toTimeoutMillis(long timeoutMs) {
    if (timeoutMs < 1 || timeoutMs > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("ADK timeout 必须在 1 到 " + Integer.MAX_VALUE + " 毫秒之间");
    }
    return (int) timeoutMs;
  }
}
