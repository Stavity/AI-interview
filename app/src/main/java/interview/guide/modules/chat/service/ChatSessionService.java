package interview.guide.modules.chat.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.chat.model.ChatSessionEntity;
import interview.guide.modules.chat.repository.ChatSessionRepository;
import interview.guide.modules.chat.repository.ChatMessageRepository;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatSessionService {
  private final ChatSessionRepository sessionRepository;
  private final ChatMessageRepository messageRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final LlmProviderRepository providerRepository;

  @Transactional
  public ChatSessionEntity createSession(String title) {
    String providerId = globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "未配置默认 Chat Provider"));
    LlmProviderEntity provider = providerRepository.findById(providerId)
        .filter(LlmProviderEntity::isEnabled)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "默认 Chat Provider 不可用: " + providerId));
    ChatSessionEntity session = new ChatSessionEntity();
    session.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
    session.setProviderId(provider.getId());
    session.setProviderFingerprint(fingerprint(provider));
    return sessionRepository.save(session);
  }

  public List<ChatSessionEntity> listSessions() {
    return sessionRepository.findAllByOrderByPinnedDescUpdatedAtDesc();
  }

  @Transactional(readOnly = true)
  public ChatSessionEntity getSession(Long sessionId) {
    return requireSession(sessionId);
  }

  @Transactional
  public void setPinned(Long sessionId, boolean pinned) {
    ChatSessionEntity session = requireSession(sessionId); session.setPinned(pinned); sessionRepository.save(session);
  }

  @Transactional
  public void archive(Long sessionId) {
    ChatSessionEntity session = requireSession(sessionId); session.setStatus(ChatSessionEntity.Status.ARCHIVED); sessionRepository.save(session);
  }

  @Transactional
  public void rename(Long sessionId, String title) {
    if (title == null || title.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能为空");
    ChatSessionEntity session = requireSession(sessionId); session.setTitle(title.trim()); sessionRepository.save(session);
  }

  @Transactional
  public void delete(Long sessionId) {
    ChatSessionEntity session = requireSession(sessionId);
    messageRepository.deleteBySessionId(sessionId);
    sessionRepository.delete(session);
  }

  private ChatSessionEntity requireSession(Long sessionId) {
    return sessionRepository.findById(sessionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Chat 会话不存在: " + sessionId));
  }

  static String fingerprint(LlmProviderEntity provider) {
    String source = provider.getBaseUrl().trim() + "\n" + provider.getModel().trim() + "\n" + provider.getTemperature();
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte value : digest) result.append(String.format("%02x", value));
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 不支持 SHA-256", exception);
    }
  }
}
