package interview.guide.modules.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.chat.model.ChatMessageEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

@ExtendWith(MockitoExtension.class)
class ChatPromptContextBuilderTest {
  @Mock private PromptSanitizer promptSanitizer;

  @Test
  @DisplayName("构建历史时排除当前用户消息与未完成助手占位")
  void shouldKeepOnlyCompletedHistoryBeforeCurrentUser() {
    ChatMessageEntity oldUser = message(ChatMessageEntity.Role.USER, "旧问题", 0, true);
    ChatMessageEntity oldAssistant = message(ChatMessageEntity.Role.ASSISTANT, "旧回答", 1, true);
    ChatMessageEntity currentUser = message(ChatMessageEntity.Role.USER, "新问题", 2, true);
    ChatMessageEntity placeholder = message(ChatMessageEntity.Role.ASSISTANT, "", 3, false);
    when(promptSanitizer.sanitize("旧问题")).thenReturn("净化旧问题");

    var history = new ChatPromptContextBuilder(promptSanitizer)
        .completedHistoryBeforeCurrentUser(List.of(oldUser, oldAssistant, currentUser, placeholder));

    assertThat(history).hasSize(2);
    assertThat(history.get(0)).isInstanceOf(UserMessage.class);
    assertThat(history.get(0).getText()).isEqualTo("净化旧问题");
    assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
    assertThat(history.get(1).getText()).isEqualTo("旧回答");
  }

  private ChatMessageEntity message(ChatMessageEntity.Role role, String content, int order, boolean completed) {
    ChatMessageEntity message = new ChatMessageEntity();
    message.setRole(role); message.setContent(content); message.setMessageOrder(order); message.setCompleted(completed);
    return message;
  }
}
