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
  @Mock private MultiRoundWorkflowService workflowService;

  @Test
  @DisplayName("创建多轮扩展时生成连续的默认四轮")
  void shouldCreateDefaultRounds() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(7L);
    when(sessionRepository.findBySessionId("session-7")).thenReturn(Optional.of(session));
    when(interviewRepository.findByInterviewSessionId(7L)).thenReturn(Optional.empty());
    when(interviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roundRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    MultiRoundInterviewEntity result = new MultiRoundInterviewService(sessionRepository, interviewRepository, roundRepository, settingRepository, providerRepository, idempotencyService, workflowService)
        .create("session-7", "provider-a", "fingerprint");

    assertThat(result.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.ROUND_READY);
    assertThat(result.getCurrentRoundNo()).isZero();
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
    MultiRoundInterviewEntity result = new MultiRoundInterviewService(sessionRepository, interviewRepository, roundRepository, settingRepository, providerRepository, idempotencyService, workflowService).createUsingDefaultProvider("session-8");
    assertThat(result.getProviderId()).isEqualTo("provider-a"); assertThat(result.getProviderFingerprint()).hasSize(64);
  }

  @Test
  @DisplayName("多轮面试保留创建时的简历与岗位快照")
  void shouldRetainResumeScopedSnapshot() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();

    interview.setResumeSnapshot("candidate resume");
    interview.setResumeSnapshotVersion("resume-v1");
    interview.setJdSnapshot("Java backend engineer");
    interview.setSkillId("java-backend");
    interview.setDifficulty("mid");
    interview.setScopeFingerprint("scope-fingerprint");

    assertThat(interview.getResumeSnapshot()).isEqualTo("candidate resume");
    assertThat(interview.getResumeSnapshotVersion()).isEqualTo("resume-v1");
    assertThat(interview.getJdSnapshot()).isEqualTo("Java backend engineer");
    assertThat(interview.getSkillId()).isEqualTo("java-backend");
    assertThat(interview.getDifficulty()).isEqualTo("mid");
    assertThat(interview.getScopeFingerprint()).isEqualTo("scope-fingerprint");
  }
}
