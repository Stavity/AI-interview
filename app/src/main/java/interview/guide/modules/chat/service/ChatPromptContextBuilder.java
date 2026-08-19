package interview.guide.modules.chat.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.chat.model.ChatMessageEntity;
import interview.guide.modules.chat.model.ChatSessionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatPromptContextBuilder {
  private static final int MAX_HISTORY_MESSAGES = 20;
  private static final int SUMMARY_THRESHOLD = 16;
  private final PromptSanitizer promptSanitizer;

  /**
   * Builds the conversation history for the LLM prompt.
   * If the session has a summary and history exceeds the threshold,
   * the summary is prepended as a system message and only recent messages are included.
   */
  public List<Message> completedHistoryBeforeCurrentUser(
      List<ChatMessageEntity> messages, ChatSessionEntity session) {
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

    List<Message> result = new ArrayList<>();

    // If we have a summary and enough history, include summary as context
    if (session != null && session.getSummary() != null && !session.getSummary().isBlank()
        && history.size() > SUMMARY_THRESHOLD) {
      result.add(new SystemMessage("之前的对话摘要：" + session.getSummary()));
    }

    int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
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

  /** Overload for backward compatibility with callers that don't pass session. */
  public List<Message> completedHistoryBeforeCurrentUser(List<ChatMessageEntity> messages) {
    return completedHistoryBeforeCurrentUser(messages, null);
  }

  public String currentUserPrompt(String message) {
    return promptSanitizer.wrapWithDelimiters("chat-user-input", promptSanitizer.sanitize(message));
  }

  /**
   * Determines whether the session needs a summary update based on message count.
   */
  public boolean needsSummaryUpdate(ChatSessionEntity session) {
    return session.getMessageCount() >= SUMMARY_THRESHOLD
        && (session.getSummary() == null || session.getSummary().isBlank());
  }
}
