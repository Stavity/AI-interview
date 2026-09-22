package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.multiround.dto.StartMultiRoundInterviewRequest;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class MultiRoundInterviewBootstrapServiceTest {
  @Mock private ResumeRepository resumeRepository;
  @Mock private ResumeAnalysisRepository analysisRepository;
  @Mock private MultiRoundInterviewRepository interviewRepository;
  @Mock private MultiRoundBootstrapPersistenceService persistenceService;
  @Mock private RedisService redisService;
  @Mock private MultiRoundWorkflowInitializationService workflowInitializationService;
  @Mock private MultiRoundAutomaticStartService automaticStartService;

  @BeforeEach
  void stubLock() {
    lenient().when(redisService.executeWithLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class), any()))
        .thenAnswer(invocation -> invocation.getArgument(4, RedisService.LockedOperation.class).execute());
  }

  private MultiRoundInterviewBootstrapService service() {
    return new MultiRoundInterviewBootstrapService(
        resumeRepository, analysisRepository, interviewRepository, persistenceService, redisService,
        workflowInitializationService, automaticStartService);
  }

  private StartMultiRoundInterviewRequest request(String jd) {
    return new StartMultiRoundInterviewRequest("java-backend", "mid", jd);
  }

  private ResumeEntity analyzedResume() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(9L);
    resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
    resume.setResumeText("candidate resume text");
    return resume;
  }

  private MultiRoundInterviewEntity interviewWithSession() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-x");
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(101L);
    interview.setInterviewSession(session);
    interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_READY);
    interview.setCurrentRoundNo(0);
    return interview;
  }

  @Test
  @DisplayName("简历未解析完成时拒绝启动")
  void shouldRejectResumeThatHasNotFinishedParsing() {
    ResumeEntity resume = new ResumeEntity();
    resume.setAnalyzeStatus(AsyncTaskStatus.PROCESSING);
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(resume));

    assertThatThrownBy(() -> service().start(9L, request("Java JD"), "key-9"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("简历尚未解析完成");
  }

  @Test
  @DisplayName("创建简历绑定会话并固化快照")
  void shouldCreateResumeBoundSessionAndImmutableSnapshot() {
    ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
    analysis.setSummary("experienced Java engineer");
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(analyzedResume()));
    when(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(9L)).thenReturn(analysis);
    when(interviewRepository.findFirstByScopeFingerprintAndStatusIn(any(), any())).thenReturn(Optional.empty());
    when(persistenceService.create(eq(9L), eq("java-backend"), eq("mid"), eq("key-9"),
        eq("Java JD"), anyString(), anyString(), anyString()))
        .thenReturn(interviewWithSession());

    var response = service().start(9L, request(" Java JD "), "key-9");

    assertThat(response.resumed()).isFalse();
    assertThat(response.interviewId()).isEqualTo(101L);
    // JD 归一化后才参与指纹与快照
    verify(persistenceService).create(eq(9L), eq("java-backend"), eq("mid"), eq("key-9"),
        eq("Java JD"), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("已有活跃面试时直接复用，不再创建")
  void shouldResumeExistingActiveInterview() {
    ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
    analysis.setSummary("summary");
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(analyzedResume()));
    when(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(9L)).thenReturn(analysis);
    when(interviewRepository.findFirstByScopeFingerprintAndStatusIn(any(), any()))
        .thenReturn(Optional.of(interviewWithSession()));

    var response = service().start(9L, request("jd"), "key-9");

    assertThat(response.resumed()).isTrue();
    verify(persistenceService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("范围指纹唯一索引冲突时回读活跃面试，返回 resumed 而非报错")
  void shouldRecoverFromScopeFingerprintConflict() {
    ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
    analysis.setSummary("summary");
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(analyzedResume()));
    when(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(9L)).thenReturn(analysis);
    // 首次与锁内复查均无活跃面试，落库时撞唯一索引，随后回读到并发请求创建的那条
    when(interviewRepository.findFirstByScopeFingerprintAndStatusIn(any(), any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(interviewWithSession()));
    when(persistenceService.create(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("uq_multi_round_active_scope"));

    var response = service().start(9L, request("jd"), "key-9");

    assertThat(response.resumed()).isTrue();
    assertThat(response.interviewId()).isEqualTo(101L);
  }
}
