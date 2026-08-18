package interview.guide.modules.chat.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.chat.model.ChatMessageEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatPromptContextBuilder {
  private static final int MAX_HISTORY_MESSAGES = 20;
  private final PromptSanitizer promptSanitizer;

  public List<Message> completedHistoryBeforeCurrentUser(List<ChatMessageEntity> messages) {
    int currentUserOrder = messages.stream()
        .filter(message -> message.isCompleted() && message.getRole() == ChatMessageEntity.Role.USER)
        .mapToInt(ChatMessageEntity::getMessageOrder)
        .max()
        .orElse(-1);
    if (currentUserOrder < 0) return List.of();
    List<ChatMessageEntity> history = messages.stream()
        .filter(ChatMessageEntity::isCompleted)
        .filter(message -> message.getMessageOrder() < currentUserOrder)
        .sorted(Comparator.comparingInt(ChatMessageEntity::getMessageOrder))
        .toList();
    int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
    List<Message> result = new ArrayList<>();
    for (ChatMessageEntity message : history.subList(from, history.size())) {
      String content = message.getContent() == null ? "" : message.getContent();
      if (message.getRole() == ChatMessageEntity.Role.USER) {
        result.add(new UserMessage(promptSanitizer.sanitize(content)));
      } else {
        result.add(new AssistantMessage(content));
      }
    }
    return result;
  }

  public String currentUserPrompt(String message) {
    return promptSanitizer.wrapWithDelimiters("chat-user-input", promptSanitizer.sanitize(message));
  }
}
