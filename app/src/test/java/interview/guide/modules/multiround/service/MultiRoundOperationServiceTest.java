package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.agent.InterviewAgentOrchestrator;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundIdempotencyEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.resume.model.ResumeEntity;
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
class MultiRoundOperationServiceTest {
  @Mock private MultiRoundRepository roundRepository;
  @Mock private MultiRoundQuestionRepository questionRepository;
  @Mock private AlgorithmSubmissionRepository submissionRepository;
  @Mock private MultiRoundIdempotencyService idempotencyService;
  @Mock private MultiRoundLifecycleService lifecycleService;
  @Mock private MultiRoundQuestionService questionService;
  @Mock private AlgorithmSubmissionService submissionService;
  @Mock private MultiRoundInterviewService interviewService;
  @Mock private MultiRoundQuestionGenerationService questionGenerationService;
  @Mock private InterviewAgentOrchestrator agentOrchestrator;
  @Mock private MultiRoundCheckpointMutationService checkpointMutationService;
  @Mock private MultiRoundLearningAdviceService learningAdviceService;
  @Mock private MultiRoundWorkflowService workflowService;
  @Mock private RedisService redisService;

  @BeforeEach
  void stubLock() {
    // 锁在单测中直接执行被保护的代码块
    lenient().when(redisService.executeWithLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class), any()))
        .thenAnswer(invocation -> invocation.getArgument(4, RedisService.LockedOperation.class).execute());
  }

  private MultiRoundOperationService service() {
    return new MultiRoundOperationService(roundRepository, questionRepository, submissionRepository,
        idempotencyService, lifecycleService, interviewService, questionService, submissionService,
        questionGenerationService, agentOrchestrator, checkpointMutationService, learningAdviceService, workflowService, redisService, new ObjectMapper());
  }

  private MultiRoundQuestionEntity boundQuestion(String sessionId) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setResume(new ResumeEntity());
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    question.setRound(round);
    return question;
  }

  @Test @DisplayName("重复回答请求不会再次修改题目")
  void shouldIgnoreDuplicateAnswer() {
    MultiRoundQuestionEntity question = boundQuestion("session-1");
    when(questionRepository.findById(4L)).thenReturn(Optional.of(question));
    when(idempotencyService.find("session-1", "ANSWER:4", "answer-key"))
        .thenReturn(Optional.of(new MultiRoundIdempotencyEntity()));

    service().answer(4L, "answer-key", "answer");

    verify(questionService, never()).answer(any(), any());
  }

  @Test @DisplayName("无简历的历史多轮会话不能写入答案")
  void shouldRejectLegacyInterviewMutationWithoutResume() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("legacy");
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    question.setRound(round);
    when(questionRepository.findById(8L)).thenReturn(Optional.of(question));

    assertThatThrownBy(() -> service().answer(8L, "answer-key", "answer"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须绑定简历");
  }

  @Test @DisplayName("Idempotency-Key 为空时直接拒绝")
  void shouldRejectBlankIdempotencyKey() {
    assertThatThrownBy(() -> service().answer(1L, "  ", "answer"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Idempotency-Key");
  }

  @Test
  @DisplayName("幂等重放按 resourceId 回读原提交，而不是重新执行")
  void shouldReplaySubmissionByResourceId() {
    MultiRoundQuestionEntity question = boundQuestion("session-2");
    when(questionRepository.findById(5L)).thenReturn(Optional.of(question));
    MultiRoundIdempotencyEntity record = new MultiRoundIdempotencyEntity();
    record.setResourceId(99L);
    when(idempotencyService.find("session-2", "ALGORITHM_SUBMIT:5", "k"))
        .thenReturn(Optional.of(record));
    AlgorithmSubmissionEntity existing = new AlgorithmSubmissionEntity();
    when(submissionRepository.findById(99L)).thenReturn(Optional.of(existing));

    var result = service().submitAlgorithm(5L, "k", "JAVA", "code");

    assertThat(result).isSameAs(existing);
    verify(submissionService, never()).submit(any(), any(), any());
  }

  @Test
  @DisplayName("同题并发提交触发唯一约束时重试一次转为更新")
  void shouldRetrySubmissionOnUniqueConflict() {
    MultiRoundQuestionEntity question = boundQuestion("session-3");
    when(questionRepository.findById(6L)).thenReturn(Optional.of(question));
    when(idempotencyService.find("session-3", "ALGORITHM_SUBMIT:6", "k2")).thenReturn(Optional.empty());
    AlgorithmSubmissionEntity submission = new AlgorithmSubmissionEntity();
    when(submissionService.submit(6L, "JAVA", "code"))
        .thenThrow(new DataIntegrityViolationException("algorithm_submissions_question_unique"))
        .thenReturn(submission);

    var result = service().submitAlgorithm(6L, "k2", "JAVA", "code");

    assertThat(result).isSameAs(submission);
    verify(submissionService, times(2)).submit(6L, "JAVA", "code");
  }

  @Test
  @DisplayName("nextRound 幂等重放返回已记录的下一轮，而不是 null")
  void shouldReplayNextRoundFromRecordedResource() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-4");
    session.setResume(new ResumeEntity());
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setInterviewSession(session);
    when(interviewService.getBySessionId("session-4")).thenReturn(interview);
    MultiRoundIdempotencyEntity record = new MultiRoundIdempotencyEntity();
    record.setResourceId(42L);
    when(idempotencyService.find("session-4", "NEXT_ROUND", "k3")).thenReturn(Optional.of(record));
    MultiRoundEntity next = new MultiRoundEntity();
    when(roundRepository.findById(42L)).thenReturn(Optional.of(next));

    var result = service().nextRound("session-4", "k3");

    assertThat(result).isSameAs(next);
    verify(lifecycleService, never()).advanceToNextRound(any());
  }

  @Test
  @DisplayName("nextRound 重放且无下一轮时返回 null，表示面试已结束")
  void shouldReplayNextRoundAsNullWhenInterviewCompleted() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-5");
    session.setResume(new ResumeEntity());
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setInterviewSession(session);
    when(interviewService.getBySessionId("session-5")).thenReturn(interview);
    // resourceId 为空才表示确实没有下一轮
    when(idempotencyService.find("session-5", "NEXT_ROUND", "k4"))
        .thenReturn(Optional.of(new MultiRoundIdempotencyEntity()));

    assertThat(service().nextRound("session-5", "k4")).isNull();
    verify(lifecycleService, never()).advanceToNextRound(any());
  }

  @Test @DisplayName("轮次报告同样要求绑定简历")
  void shouldRequireResumeForRoundReport() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("legacy");
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview);
    when(roundRepository.findById(11L)).thenReturn(Optional.of(round));

    assertThatThrownBy(() -> service().getRoundReport(11L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须绑定简历");
  }

  @Test
  @DisplayName("所有题目完成后由服务端自动结束当前轮并准备下一轮")
  void shouldAutomaticallyProgressAfterAllQuestionsComplete() {
    MultiRoundQuestionEntity completed = boundQuestion("session-auto");
    completed.setStatus(MultiRoundQuestionEntity.Status.ANSWERED);
    MultiRoundEntity round = completed.getRound();
    round.setId(12L);
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    round.setQuestionLimit(1);
    MultiRoundInterviewEntity interview = round.getInterview();
    interview.setId(3L);
    when(roundRepository.findById(12L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(12L)).thenReturn(java.util.List.of(completed));
    when(idempotencyService.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    when(lifecycleService.finish(12L)).thenReturn(round);
    when(interviewService.getBySessionId("session-auto")).thenReturn(interview);
    when(lifecycleService.advanceToNextRound(3L)).thenReturn(null);

    service().progressAfterCandidateAction(12L);

    verify(lifecycleService).finish(12L);
    verify(lifecycleService).advanceToNextRound(3L);
    verify(questionGenerationService, never()).draft(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }
}
