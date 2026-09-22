package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundLifecycleServiceTest {
  @Mock private MultiRoundRepository roundRepository;
  @Mock private MultiRoundQuestionRepository questionRepository;
  @Mock private MultiRoundRoundReportService roundReportService;
  @Mock private MultiRoundInterviewRepository interviewRepository;
  @Mock private MultiRoundQuestionGenerationService questionGenerationService;
  @Mock private MultiRoundPlanningContextFactory planningContextFactory;
  @Mock private MultiRoundWorkflowService workflowService;

  private MultiRoundLifecycleService service() {
    return new MultiRoundLifecycleService(roundRepository, questionRepository, interviewRepository,
        roundReportService, questionGenerationService, planningContextFactory, workflowService);
  }

  private InterviewPlan plan(int roundNo, String type) {
    return new InterviewPlan(roundNo, type, 0, List.of("resume-project"), 1);
  }

  @Test @DisplayName("commitStart 将当前待开始轮次置为进行中并写入题目")
  void shouldStartCurrentPendingRound() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(1);
    round.setRoundType(MultiRoundEntity.Type.ALGORITHM); round.setQuestionLimit(2);
    when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(1L)).thenReturn(List.of());
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var drafted = List.of(
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "q1", "a1"),
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "q2", "a2"));
    service().commitStart(1L, plan(1, "ALGORITHM"), drafted);

    assertThat(round.getStatus()).isEqualTo(MultiRoundEntity.Status.IN_PROGRESS);
    assertThat(interview.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.IN_PROGRESS);
    assertThat(interview.getCurrentRoundNo()).isEqualTo(1);
    // 题目必须真的落库，缺口为 2 时应整批写入
    verify(questionGenerationService).persist(any(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("上一轮未完成时不能启动下一轮")
  void shouldRejectStartingNextRoundWhilePreviousInProgress() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(7L);
    interview.setCurrentRoundNo(1);
    MultiRoundEntity first = new MultiRoundEntity();
    first.setRoundNo(1); first.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    MultiRoundEntity second = new MultiRoundEntity();
    second.setInterview(interview); second.setRoundNo(2);
    second.setRoundType(MultiRoundEntity.Type.TECHNICAL_1); second.setQuestionLimit(3);
    when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(7L)).thenReturn(List.of(first, second));

    // roundNo(2) == currentRoundNo(1) + 1 会通过旧守卫，必须再看上一轮是否 COMPLETED
    assertThatThrownBy(() -> service().commitStart(2L, plan(2, "TECHNICAL_1"), List.of()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("上一轮次尚未完成");
    assertThat(second.getStatus()).isEqualTo(MultiRoundEntity.Status.PENDING);
  }

  @Test
  @DisplayName("上一轮已完成时可以启动下一轮")
  void shouldAllowStartingNextRoundAfterPreviousCompleted() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(7L);
    interview.setCurrentRoundNo(1);
    MultiRoundEntity first = new MultiRoundEntity();
    first.setRoundNo(1); first.setStatus(MultiRoundEntity.Status.COMPLETED);
    MultiRoundEntity second = new MultiRoundEntity();
    second.setInterview(interview); second.setRoundNo(2);
    second.setRoundType(MultiRoundEntity.Type.TECHNICAL_1); second.setQuestionLimit(1);
    when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(7L)).thenReturn(List.of(first, second));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(2L)).thenReturn(List.of());
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service().commitStart(2L, plan(2, "TECHNICAL_1"), List.of(
        new MultiRoundQuestionGenerationService.GeneratedQuestion("TECHNICAL", "resume-project", "q", "a")));

    assertThat(second.getStatus()).isEqualTo(MultiRoundEntity.Status.IN_PROGRESS);
    assertThat(interview.getCurrentRoundNo()).isEqualTo(2);
  }

  @Test
  @DisplayName("同一进行中轮次可重入，用于 LLM 失败后补齐题目")
  void shouldBeReentrantForInProgressRound() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(7L);
    interview.setCurrentRoundNo(1);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(1);
    round.setRoundType(MultiRoundEntity.Type.ALGORITHM); round.setQuestionLimit(2);
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    // 已有 1 题，仍缺 1 题
    MultiRoundQuestionEntity existing = new MultiRoundQuestionEntity();
    existing.setSequenceNo(1);
    when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(existing));
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service().commitStart(1L, plan(1, "ALGORITHM"), List.of(
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "q2", "a2")));

    // 从已有数量之后接着编号，不与既有 sequence_no 冲突
    verify(questionGenerationService).persist(any(), any(), any(), org.mockito.ArgumentMatchers.eq(1));
    assertThat(round.getStatus()).isEqualTo(MultiRoundEntity.Status.IN_PROGRESS);
  }

  @Test @DisplayName("题目已满额时不再重复写入")
  void shouldNotPersistWhenQuestionsAlreadyComplete() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(1);
    round.setRoundType(MultiRoundEntity.Type.ALGORITHM); round.setQuestionLimit(1);
    MultiRoundQuestionEntity existing = new MultiRoundQuestionEntity();
    when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(existing));
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service().commitStart(1L, plan(1, "ALGORITHM"), List.of());

    verify(questionGenerationService, never()).persist(any(), any(), any(), anyInt());
  }

  @Test @DisplayName("题目全部答完后才能显式结束轮次")
  void shouldFinishRoundAfterAllQuestionsAnswered() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(2L);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(2); round.setQuestionLimit(1);
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    question.setStatus(MultiRoundQuestionEntity.Status.ANSWERED);
    when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(2L)).thenReturn(List.of(question));
    MultiRoundEntity next = new MultiRoundEntity(); next.setRoundNo(3);
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(2L)).thenReturn(List.of(round, next));
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roundReportService.build(round, List.of(question))).thenReturn("{\"answeredQuestionCount\":1}");

    service().finish(2L);

    assertThat(round.getStatus()).isEqualTo(MultiRoundEntity.Status.COMPLETED);
    assertThat(interview.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.ROUND_COMPLETED);
    assertThat(round.getReportJson()).contains("answeredQuestionCount");
  }

  @Test @DisplayName("题目不足时不能结束轮次")
  void shouldRejectFinishWhenQuestionsMissing() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(1); round.setQuestionLimit(3);
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(1L)).thenReturn(List.of());

    assertThatThrownBy(() -> service().finish(1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仍有未完成题目");
  }

  @Test @DisplayName("preflightStart 对已完成轮次直接返回，无需调用 LLM")
  void shouldSkipPlanningForCompletedRound() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-1");
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(1);
    round.setStatus(MultiRoundEntity.Status.COMPLETED);
    when(roundRepository.findById(1L)).thenReturn(Optional.of(round));

    var preflight = service().preflightStart(1L);

    assertThat(preflight.alreadyCompleted()).isTrue();
    assertThat(preflight.context()).isNull();
    verify(planningContextFactory, never()).create(any(), any());
  }

  @Test @DisplayName("末节点结束后整场面试标记完成，不依赖节点是否为 HR")
  void shouldCompleteInterviewAfterTerminalNode() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(4L);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(4); round.setQuestionLimit(1);
    round.setRoundType(MultiRoundEntity.Type.TECHNICAL_2);
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    question.setStatus(MultiRoundQuestionEntity.Status.ANSWERED);
    when(roundRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(4L)).thenReturn(List.of(question));
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(4L)).thenReturn(List.of(round));
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roundReportService.build(any(), any())).thenReturn("{}");

    service().finish(4L);

    assertThat(interview.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.COMPLETED);
    assertThat(interview.getCompletedAt()).isNotNull();
  }

  @Test @DisplayName("当前轮次未完成时不能进入下一轮")
  void shouldRejectAdvanceWhenCurrentRoundIncomplete() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(3L);
    interview.setCurrentRoundNo(1);
    MultiRoundEntity round = new MultiRoundEntity();
    round.setRoundNo(1); round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview));
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(3L)).thenReturn(List.of(round));

    assertThatThrownBy(() -> service().advanceToNextRound(3L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("尚未完成");
  }

  @Test @DisplayName("当前轮次完成后返回下一轮并置为就绪")
  void shouldAdvanceToNextRound() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(3L);
    interview.setCurrentRoundNo(1);
    MultiRoundEntity first = new MultiRoundEntity();
    first.setRoundNo(1); first.setStatus(MultiRoundEntity.Status.COMPLETED);
    first.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    MultiRoundEntity second = new MultiRoundEntity();
    second.setRoundNo(2); second.setRoundType(MultiRoundEntity.Type.TECHNICAL_1);
    when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview));
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(3L)).thenReturn(List.of(first, second));

    MultiRoundEntity next = service().advanceToNextRound(3L);

    assertThat(next).isSameAs(second);
    assertThat(interview.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.ROUND_READY);
  }
}
