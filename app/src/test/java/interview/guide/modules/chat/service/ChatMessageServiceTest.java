package interview.guide.modules.chat.service;

import interview.guide.modules.chat.model.ChatMessageEntity;
import interview.guide.modules.chat.model.ChatSessionEntity;
import interview.guide.modules.chat.repository.ChatMessageRepository;
import interview.guide.modules.chat.repository.ChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {
  @Mock private ChatSessionRepository sessionRepository;
  @Mock private ChatMessageRepository messageRepository;

  @Test
  @DisplayName("准备流式消息时保存用户消息和未完成助手占位消息")
  void shouldPrepareUserAndAssistantPlaceholder() {
    ChatSessionEntity session = new ChatSessionEntity(); session.setId(3L);
    session.setMessageCount(4);
    when(sessionRepository.findById(3L)).thenReturn(Optional.of(session));
    when(messageRepository.save(any(ChatMessageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(sessionRepository.save(any(ChatSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    new ChatMessageService(sessionRepository, messageRepository).prepareStreamMessage(3L, "你好");

    ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
    verify(messageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues().getFirst().getRole()).isEqualTo(ChatMessageEntity.Role.USER);
    assertThat(captor.getAllValues().getFirst().getMessageOrder()).isEqualTo(4);
    assertThat(captor.getAllValues().get(1).getRole()).isEqualTo(ChatMessageEntity.Role.ASSISTANT);
    assertThat(captor.getAllValues().get(1).isCompleted()).isFalse();
    assertThat(captor.getAllValues().get(1).getMessageOrder()).isEqualTo(5);
    assertThat(session.getMessageCount()).isEqualTo(6);
  }

  @Test
  @DisplayName("读取会话消息时按消息序号返回")
  void shouldListMessagesBySessionInOrder() {
    ChatMessageEntity user = new ChatMessageEntity();
    user.setMessageOrder(0);
    ChatMessageEntity assistant = new ChatMessageEntity();
    assistant.setMessageOrder(1);
    when(sessionRepository.existsById(3L)).thenReturn(true);
    when(messageRepository.findBySessionIdOrderByMessageOrderAsc(3L)).thenReturn(List.of(user, assistant));

    List<ChatMessageEntity> messages = new ChatMessageService(sessionRepository, messageRepository)
        .listMessages(3L);

    assertThat(messages).containsExactly(user, assistant);
  }

  @Test
  @DisplayName("仅失败助手消息可重试，并复用其前一条用户消息")
  void shouldRetryFailedAssistantWithoutCreatingUserMessage() {
    ChatSessionEntity session = new ChatSessionEntity(); session.setId(3L);
    ChatMessageEntity user = new ChatMessageEntity(); user.setRole(ChatMessageEntity.Role.USER); user.setContent("解释一致性"); user.setMessageOrder(2);
    ChatMessageEntity failed = new ChatMessageEntity(); failed.setId(8L); failed.setSession(session); failed.setRole(ChatMessageEntity.Role.ASSISTANT); failed.setMessageOrder(3); failed.setCompleted(true); failed.setFailureCode("AI_SERVICE_ERROR");
    when(messageRepository.findById(8L)).thenReturn(Optional.of(failed));
    when(messageRepository.findBySessionIdOrderByMessageOrderAsc(any())).thenReturn(List.of(user, failed));
    when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var retry = new ChatMessageService(sessionRepository, messageRepository).retryFailedAssistant(8L);

    assertThat(retry.userContent()).isEqualTo("解释一致性");
    assertThat(failed.isCompleted()).isFalse();
    assertThat(failed.getFailureCode()).isNull();
    verify(messageRepository).save(failed);
  }
}
