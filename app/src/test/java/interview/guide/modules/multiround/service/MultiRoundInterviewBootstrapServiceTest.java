package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.multiround.dto.StartMultiRoundInterviewRequest;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundInterviewBootstrapServiceTest {
  @Mock private ResumeRepository resumeRepository;
  @Mock private ResumeAnalysisRepository analysisRepository;
  @Mock private InterviewSessionRepository sessionRepository;
  @Mock private MultiRoundInterviewRepository interviewRepository;
  @Mock private MultiRoundInterviewService interviewService;

  @Test
  void shouldRejectResumeThatHasNotFinishedParsing() {
    ResumeEntity resume = new ResumeEntity();
    resume.setAnalyzeStatus(AsyncTaskStatus.PROCESSING);
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(resume));
    MultiRoundInterviewBootstrapService service = new MultiRoundInterviewBootstrapService(
        resumeRepository, analysisRepository, sessionRepository, interviewRepository, interviewService);

    assertThatThrownBy(() -> service.start(9L,
        new StartMultiRoundInterviewRequest("java-backend", "mid", "Java JD", false), "key-9"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("简历尚未解析完成");
  }

  @Test
  void shouldCreateResumeBoundSessionAndImmutableSnapshot() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(9L);
    resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
    resume.setResumeText("candidate resume text");
    ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
    analysis.setSummary("experienced Java engineer");
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(101L);
    interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_READY);
    interview.setCurrentRoundNo(0);
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(resume));
    when(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(9L)).thenReturn(analysis);
    when(interviewRepository.findFirstByScopeFingerprintAndStatusIn(any(), any())).thenReturn(Optional.empty());
    when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(interviewService.createUsingDefaultProvider(any(), any())).thenReturn(interview);
    when(interviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MultiRoundInterviewBootstrapService service = new MultiRoundInterviewBootstrapService(
        resumeRepository, analysisRepository, sessionRepository, interviewRepository, interviewService);

    var response = service.start(9L,
        new StartMultiRoundInterviewRequest("java-backend", "mid", " Java JD ", false), "key-9");

    verify(sessionRepository).save(any(InterviewSessionEntity.class));
    assertThat(response.resumed()).isFalse();
    assertThat(response.interviewId()).isEqualTo(101L);
    assertThat(interview.getResumeSnapshot()).contains("candidate resume text");
    assertThat(interview.getJdSnapshot()).isEqualTo("Java JD");
    assertThat(interview.getSkillId()).isEqualTo("java-backend");
  }
}
