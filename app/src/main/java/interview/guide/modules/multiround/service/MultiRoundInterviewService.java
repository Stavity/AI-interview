package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class MultiRoundInterviewService {
  private final InterviewSessionRepository sessionRepository; private final MultiRoundInterviewRepository interviewRepository; private final MultiRoundRepository roundRepository;
  private final LlmGlobalSettingRepository settingRepository; private final LlmProviderRepository providerRepository;
  private final MultiRoundIdempotencyService idempotencyService;
  @Transactional
  public MultiRoundInterviewEntity createUsingDefaultProvider(String sessionId) {
    String providerId = settingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID).map(LlmGlobalSettingEntity::getDefaultChatProviderId).orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "未配置默认 Chat Provider"));
    LlmProviderEntity provider = providerRepository.findById(providerId).filter(LlmProviderEntity::isEnabled).orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "默认 Chat Provider 不可用: " + providerId));
    return create(sessionId, provider.getId(), fingerprint(provider));
  }
  @Transactional
  public MultiRoundInterviewEntity createUsingDefaultProvider(String sessionId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能为空");
    var existing = idempotencyService.findCompleted(sessionId, "CREATE", idempotencyKey); if (existing.isPresent()) return existing.get();
    MultiRoundInterviewEntity created = createUsingDefaultProvider(sessionId); idempotencyService.record(sessionId, "CREATE", idempotencyKey, created); return created;
  }
  @Transactional(readOnly = true)
  public MultiRoundInterviewEntity getBySessionId(String sessionId) {
    var session = sessionRepository.findBySessionId(sessionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在: " + sessionId));
    return interviewRepository.findByInterviewSessionId(session.getId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试不存在: " + sessionId));
  }
  @Transactional
  public MultiRoundInterviewEntity create(String sessionId, String providerId, String providerFingerprint) {
    var session = sessionRepository.findBySessionId(sessionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在: " + sessionId));
    var existing = interviewRepository.findByInterviewSessionId(session.getId()); if (existing.isPresent()) return existing.get();
    var interview = new MultiRoundInterviewEntity(); interview.setInterviewSession(session); interview.setProviderId(providerId); interview.setProviderFingerprint(providerFingerprint); interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_READY); interview = interviewRepository.save(interview);
    MultiRoundInterviewEntity saved = interview;
    List<String> roundTypes = MultiRoundStateMachine.defaultRounds();
    // 原实现直接使用硬编码列表，绕过了 validatePlan，使其成为永不执行的死代码
    MultiRoundStateMachine.validatePlan(roundTypes);
    List<MultiRoundEntity> rounds = new java.util.ArrayList<>(roundTypes.size());
    for (int i = 0; i < roundTypes.size(); i++) {
      var round = new MultiRoundEntity();
      round.setInterview(saved);
      round.setRoundNo(i + 1);
      round.setRoundType(MultiRoundEntity.Type.valueOf(roundTypes.get(i)));
      round.setQuestionLimit(3);
      rounds.add(round);
    }
    roundRepository.saveAll(rounds); return interview;
  }
  private String fingerprint(LlmProviderEntity provider) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest((provider.getBaseUrl().trim() + "\n" + provider.getModel().trim() + "\n" + provider.getTemperature()).getBytes(StandardCharsets.UTF_8)); StringBuilder value = new StringBuilder(64); for (byte b : digest) value.append(String.format("%02x", b)); return value.toString(); } catch (Exception error) { throw new IllegalStateException(error); } }
}
