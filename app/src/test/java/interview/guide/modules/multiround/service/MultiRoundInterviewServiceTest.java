package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundInterviewServiceTest {
  @Mock private InterviewSessionRepository sessionRepository;
  @Mock private MultiRoundInterviewRepository interviewRepository;
  @Mock private MultiRoundRepository roundRepository;
  @Mock private LlmGlobalSettingRepository settingRepository;
  @Mock private LlmProviderRepository providerRepository;
  @Mock private MultiRoundIdempotencyService idempotencyService;

  @Test
  @DisplayName("创建多轮扩展时生成连续的默认四轮")
  void shouldCreateDefaultRounds() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(7L);
    when(sessionRepository.findBySessionId("session-7")).thenReturn(Optional.of(session));
    when(interviewRepository.findByInterviewSessionId(7L)).thenReturn(Optional.empty());
    when(interviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roundRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    MultiRoundInterviewEntity result = new MultiRoundInterviewService(sessionRepository, interviewRepository, roundRepository, settingRepository, providerRepository, idempotencyService)
        .create("session-7", "provider-a", "fingerprint");

    assertThat(result.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.ROUND_READY);
    assertThat(result.getCurrentRoundNo()).isZero();

    // 原测试只看 status/currentRoundNo——即使一轮都没创建也能通过
    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
    org.mockito.Mockito.verify(roundRepository).saveAll(captor.capture());
    java.util.List<interview.guide.modules.multiround.model.MultiRoundEntity> rounds = captor.getValue();
    assertThat(rounds).hasSize(4);
    assertThat(rounds).extracting(r -> r.getRoundType().name())
        .containsExactly("ALGORITHM", "TECHNICAL_1", "TECHNICAL_2", "HR");
    assertThat(rounds).extracting(interview.guide.modules.multiround.model.MultiRoundEntity::getRoundNo)
        .containsExactly(1, 2, 3, 4);
    assertThat(rounds).allSatisfy(round -> assertThat(round.getQuestionLimit()).isPositive());
  }

  @Test
  @DisplayName("创建多轮会话时固化当前默认 Chat Provider")
  void shouldFreezeDefaultProvider() {
    InterviewSessionEntity session = new InterviewSessionEntity(); session.setId(8L);
    LlmGlobalSettingEntity setting = new LlmGlobalSettingEntity(); setting.setDefaultChatProviderId("provider-a");
    LlmProviderEntity provider = new LlmProviderEntity(); provider.setId("provider-a"); provider.setBaseUrl("https://example.test"); provider.setModel("model-a"); provider.setTemperature(0.7); provider.setEnabled(true);
    when(sessionRepository.findBySessionId("session-8")).thenReturn(Optional.of(session));
    when(interviewRepository.findByInterviewSessionId(8L)).thenReturn(Optional.empty());
    when(settingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)).thenReturn(Optional.of(setting));
    when(providerRepository.findById("provider-a")).thenReturn(Optional.of(provider));
    when(interviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0)); when(roundRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MultiRoundInterviewEntity result = new MultiRoundInterviewService(sessionRepository, interviewRepository, roundRepository, settingRepository, providerRepository, idempotencyService).createUsingDefaultProvider("session-8");
    assertThat(result.getProviderId()).isEqualTo("provider-a"); assertThat(result.getProviderFingerprint()).hasSize(64);
  }

  @Test
  @DisplayName("已存在多轮扩展时不重复创建轮次")
  void shouldNotRecreateRoundsWhenInterviewExists() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(7L);
    MultiRoundInterviewEntity existing = new MultiRoundInterviewEntity();
    existing.setId(55L);
    when(sessionRepository.findBySessionId("session-7")).thenReturn(Optional.of(session));
    when(interviewRepository.findByInterviewSessionId(7L)).thenReturn(Optional.of(existing));

    var result = new MultiRoundInterviewService(sessionRepository, interviewRepository, roundRepository,
        settingRepository, providerRepository, idempotencyService)
        .create("session-7", "provider-a", "fingerprint");

    assertThat(result).isSameAs(existing);
    org.mockito.Mockito.verify(roundRepository, org.mockito.Mockito.never()).saveAll(any());
  }
}
