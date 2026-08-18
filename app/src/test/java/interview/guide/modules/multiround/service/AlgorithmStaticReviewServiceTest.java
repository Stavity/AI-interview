package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

@ExtendWith(MockitoExtension.class)
class AlgorithmStaticReviewServiceTest {
  @Mock private LlmProviderRegistry registry;
  @Mock private StructuredOutputInvoker invoker;
  @Mock private ChatClient client;

  @Test @DisplayName("静态评审使用受控 plain Client 并返回结构化结果")
  void shouldUsePlainClientForStructuredStaticReview() {
    when(registry.getPlainChatClient("provider-a")).thenReturn(client);
    AlgorithmStaticReviewService.Review expected = new AlgorithmStaticReviewService.Review(80, "哈希表", "O(n)", "O(n)", List.of("空数组"), List.of(), List.of("补充边界处理"));
    when(invoker.invoke(eq(client), any(), any(), any(), any(), any(), any(), any())).thenReturn(expected);

    var result = new AlgorithmStaticReviewService(registry, invoker).review("provider-a", "JAVA", "class Solution {} ");

    assertThat(result.score()).isEqualTo(80);
    assertThat(result.timeComplexity()).isEqualTo("O(n)");
  }
}
