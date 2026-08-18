package interview.guide.modules.chat.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.chat.model.ChatMessageEntity;
import interview.guide.modules.chat.model.ChatSessionEntity;
import interview.guide.modules.chat.repository.ChatMessageRepository;
import interview.guide.modules.chat.repository.ChatSessionRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {
  private final ChatSessionRepository sessionRepository;
  private final ChatMessageRepository messageRepository;

  @Transactional
  public Long prepareStreamMessage(Long sessionId, String content) {
    ChatSessionEntity session = sessionRepository.findById(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Chat 会话不存在: " + sessionId));
    if (session.getStatus() != ChatSessionEntity.Status.ACTIVE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "Chat 会话已归档");
    }
    int order = session.getMessageCount();
    messageRepository.save(message(session, ChatMessageEntity.Role.USER, content, order, true));
    ChatMessageEntity assistant = message(session, ChatMessageEntity.Role.ASSISTANT, "", order + 1, false);
    assistant = messageRepository.save(assistant);
    session.setMessageCount(order + 2);
    sessionRepository.save(session);
    return assistant.getId();
  }

  @Transactional(readOnly = true)
  public List<ChatMessageEntity> listMessages(Long sessionId) {
    if (!sessionRepository.existsById(sessionId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "Chat 会话不存在: " + sessionId);
    }
    return messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId);
  }

  @Transactional(readOnly = true)
  public Page<ChatMessageEntity> listMessagesPage(Long sessionId, Pageable pageable) {
    if (!sessionRepository.existsById(sessionId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "Chat 会话不存在: " + sessionId);
    }
    return messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId, pageable);
  }

  @Transactional
  public void completeStreamMessage(Long messageId, String content) {
    ChatMessageEntity message = messageRepository.findById(messageId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Chat 消息不存在: " + messageId));
    message.setContent(content);
    message.setCompleted(true);
    message.setFailureCode(null);
    messageRepository.save(message);
  }

  @Transactional
  public void failStreamMessage(Long messageId, String failureCode) {
    ChatMessageEntity message = messageRepository.findById(messageId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Chat 消息不存在: " + messageId));
    message.setCompleted(true);
    message.setFailureCode(failureCode);
    messageRepository.save(message);
  }

  @Transactional
  public RetryRequest retryFailedAssistant(Long messageId) {
    ChatMessageEntity assistant = messageRepository.findById(messageId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Chat 消息不存在: " + messageId));
    if (assistant.getRole() != ChatMessageEntity.Role.ASSISTANT || !assistant.isCompleted() || assistant.getFailureCode() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "只有失败的助手消息可以重试");
    }
    List<ChatMessageEntity> messages = messageRepository.findBySessionIdOrderByMessageOrderAsc(assistant.getSession().getId());
    ChatMessageEntity user = messages.stream()
        .filter(item -> item.getMessageOrder() == assistant.getMessageOrder() - 1 && item.getRole() == ChatMessageEntity.Role.USER)
        .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "失败消息缺少对应用户消息"));
    assistant.setContent("");
    assistant.setCompleted(false);
    assistant.setFailureCode(null);
    messageRepository.save(assistant);
    return new RetryRequest(assistant.getSession().getId(), assistant.getId(), user.getContent());
  }

  public record RetryRequest(Long sessionId, Long assistantMessageId, String userContent) {}

  private ChatMessageEntity message(ChatSessionEntity session, ChatMessageEntity.Role role,
                                    String content, int order, boolean completed) {
    ChatMessageEntity message = new ChatMessageEntity();
    message.setSession(session);
    message.setRole(role);
    message.setContent(content);
    message.setMessageOrder(order);
    message.setCompleted(completed);
    return message;
  }
}
