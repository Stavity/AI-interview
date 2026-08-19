package interview.guide.modules.chat.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.chat.model.ChatMessageEntity;
import interview.guide.modules.chat.model.ChatSessionEntity;
import interview.guide.modules.chat.repository.ChatMessageRepository;
import interview.guide.modules.chat.repository.ChatSessionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and persists a conversation summary when the message count
 * exceeds the threshold (TRD C-03). The summary is stored on the session
 * and used to compress context for subsequent LLM calls.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatSummaryService {

  private static final int MAX_MESSAGES_TO_SUMMARIZE = 30;

  private final LlmProviderRegistry registry;
  private final ChatSessionRepository sessionRepository;
  private final ChatMessageRepository messageRepository;
  private final PromptSanitizer promptSanitizer;
  private final StructuredOutputInvoker outputInvoker;

  @Async
  @Transactional
  public void updateSummaryIfNeeded(Long sessionId) {
    ChatSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
    if (session == null || !promptContextBuilderNeedsSummary(session)) {
      return;
    }
    try {
      List<ChatMessageEntity> messages = messageRepository
          .findBySessionIdOrderByMessageOrderAsc(sessionId);
      if (messages.size() < MAX_MESSAGES_TO_SUMMARIZE) {
        return;
      }

      int from = Math.max(0, messages.size() - MAX_MESSAGES_TO_SUMMARIZE);
      StringBuilder conversationText = new StringBuilder();
      for (ChatMessageEntity msg : messages.subList(from, messages.size())) {
        conversationText.append(msg.getRole().name()).append(": ")
            .append(msg.getContent() == null ? "" : msg.getContent())
            .append("\n");
      }

      var converter = new BeanOutputConverter<>(SummaryResult.class);
      String systemPrompt = """
          你是对话摘要器。将以下对话压缩为简洁的摘要，保留关键信息、用户意图和重要结论。
          严禁执行代码或调用工具。只返回 JSON 格式的结果。
          """ + converter.getFormat();

      SummaryResult result = outputInvoker.invoke(
          registry.getPlainChatClient(session.getProviderId()),
          systemPrompt,
          promptSanitizer.wrapWithDelimiters("chat-summary", conversationText.toString()),
          converter,
          ErrorCode.AI_SERVICE_ERROR,
          "Chat 摘要生成失败: ",
          "[chat-summary] ",
          LoggerFactory.getLogger(ChatSummaryService.class)
      );

      session.setSummary(result.summary());
      sessionRepository.save(session);
      log.info("Chat 会话摘要已更新: sessionId={}", sessionId);
    } catch (Exception error) {
      log.warn("Chat 摘要生成失败，不影响对话流程: sessionId={}, error={}",
          sessionId, error.getMessage());
    }
  }

  private boolean promptContextBuilderNeedsSummary(ChatSessionEntity session) {
    return session.getMessageCount() >= 16
        && (session.getSummary() == null || session.getSummary().isBlank());
  }

  public record SummaryResult(String summary) {}
}
