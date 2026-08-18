package interview.guide.modules.chat.service;

import interview.guide.modules.chat.model.ChatSessionEntity;
import interview.guide.modules.chat.repository.ChatSessionRepository;
import interview.guide.modules.chat.repository.ChatMessageRepository;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {
  @Mock private ChatSessionRepository sessionRepository;
  @Mock private ChatMessageRepository messageRepository;
  @Mock private LlmGlobalSettingRepository globalSettingRepository;
  @Mock private LlmProviderRepository providerRepository;

  @Test
  @DisplayName("创建普通 Chat 会话时固化默认 Provider 和非敏感指纹")
  void shouldFreezeDefaultProviderOnSessionCreation() {
    LlmGlobalSettingEntity settings = LlmGlobalSettingEntity.builder()
        .id(1L).defaultChatProviderId("dashscope").defaultEmbeddingProviderId("dashscope").build();
    LlmProviderEntity provider = LlmProviderEntity.builder()
        .id("dashscope").baseUrl("https://example.test/v1").model("qwen-plus")
        .temperature(0.2).enabled(true).build();
    when(globalSettingRepository.findById(1L)).thenReturn(Optional.of(settings));
    when(providerRepository.findById("dashscope")).thenReturn(Optional.of(provider));
    when(sessionRepository.save(any(ChatSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    new ChatSessionService(sessionRepository, messageRepository, globalSettingRepository, providerRepository).createSession("新对话");

    ArgumentCaptor<ChatSessionEntity> captor = ArgumentCaptor.forClass(ChatSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    ChatSessionEntity saved = captor.getValue();
    assertThat(saved.getProviderId()).isEqualTo("dashscope");
    assertThat(saved.getProviderFingerprint()).hasSize(64);
    assertThat(saved.getStatus()).isEqualTo(ChatSessionEntity.Status.ACTIVE);
  }

  @Test
  @DisplayName("可置顶和归档既有会话")
  void shouldPinAndArchiveSession() {
    ChatSessionEntity session = new ChatSessionEntity();
    when(sessionRepository.findById(6L)).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(ChatSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    ChatSessionService service = new ChatSessionService(sessionRepository, messageRepository, globalSettingRepository, providerRepository);
    service.setPinned(6L, true);
    service.archive(6L);
    assertThat(session.isPinned()).isTrue();
    assertThat(session.getStatus()).isEqualTo(ChatSessionEntity.Status.ARCHIVED);
  }
  @Test @DisplayName("重命名和删除会话时同步删除会话消息")
  void shouldRenameAndDeleteSessionWithMessages() {
    ChatSessionEntity session = new ChatSessionEntity();
    when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));
    ChatSessionService service = new ChatSessionService(sessionRepository, messageRepository, globalSettingRepository, providerRepository);
    service.rename(7L, "系统设计讨论");
    service.delete(7L);
    assertThat(session.getTitle()).isEqualTo("系统设计讨论");
    verify(messageRepository).deleteBySessionId(7L);
    verify(sessionRepository).delete(session);
  }
}
