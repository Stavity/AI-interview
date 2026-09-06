package interview.guide.modules.multiround.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundIdempotencyEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundOperationServiceTest {
  @Mock private MultiRoundRepository roundRepository;
  @Mock private MultiRoundQuestionRepository questionRepository;
  @Mock private AlgorithmSubmissionRepository submissionRepository;
  @Mock private MultiRoundIdempotencyService idempotencyService;
  @Mock private MultiRoundLifecycleService lifecycleService;
  @Mock private MultiRoundQuestionService questionService;
  @Mock private AlgorithmSubmissionService submissionService;
  @Mock private MultiRoundInterviewService interviewService;
  @Mock private AlgorithmSubmissionReviewService algorithmReviewService;

  @Test @DisplayName("重复回答请求不会再次修改题目")
  void shouldIgnoreDuplicateAnswer() {
    InterviewSessionEntity session = new InterviewSessionEntity(); session.setSessionId("session-1"); session.setResume(new ResumeEntity());
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity(); round.setInterview(interview);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity(); question.setRound(round);
    when(questionRepository.findById(4L)).thenReturn(Optional.of(question));
    when(idempotencyService.findCompleted("session-1", "ANSWER:4", "answer-key")).thenReturn(Optional.of(interview));

    service().answer(4L, "answer-key", "answer");

    verify(questionService, never()).answer(any(), any());
  }

  @Test @DisplayName("无简历的历史多轮会话不能写入答案")
  void shouldRejectLegacyInterviewMutationWithoutResume() {
    InterviewSessionEntity session = new InterviewSessionEntity(); session.setSessionId("legacy");
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity(); round.setInterview(interview);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity(); question.setRound(round);
    when(questionRepository.findById(8L)).thenReturn(Optional.of(question));

    assertThatThrownBy(() -> service().answer(8L, "answer-key", "answer"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须绑定简历");
  }

  private MultiRoundOperationService service() {
    return new MultiRoundOperationService(roundRepository, questionRepository, submissionRepository, idempotencyService, lifecycleService, interviewService, questionService, submissionService, algorithmReviewService);
  }
}
