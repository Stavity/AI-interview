package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.common.ai.agent.InterviewAgentOrchestrator;
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
  @Mock private InterviewAgentOrchestrator agentOrchestrator;
  @Mock private MultiRoundPlanningContextFactory planningContextFactory;
  @Mock private MultiRoundWorkflowService workflowService;
  @Mock private MultiRoundFinalReportService finalReportService;

  @Test @DisplayName("只允许当前待开始轮次进入进行中")
  void shouldStartCurrentPendingRound() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
    interview.setInterviewSession(session);
    MultiRoundEntity round = new MultiRoundEntity(); round.setInterview(interview); round.setRoundNo(1); round.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    InterviewPlanningContext context = new InterviewPlanningContext("resume", "jd", "java", "mid", 1,
        "ALGORITHM", List.of("resume-project"), List.of(), List.of(), List.of(), 1, 2);
    when(planningContextFactory.create(any(), any())).thenReturn(context);
    when(agentOrchestrator.plan(any(), any(), any(InterviewPlanningContext.class))).thenReturn(new InterviewPlan(1, "ALGORITHM", 0, List.of("resume-project"), 1));
    when(questionGenerationService.ensureQuestionsGenerated(any(), any(), any())).thenReturn(List.of());

    when(workflowService.usesStaticExecutor(interview)).thenReturn(true);
    new MultiRoundLifecycleService(roundRepository, questionRepository, interviewRepository, roundReportService,
        questionGenerationService, agentOrchestrator, planningContextFactory, workflowService,
        finalReportService).start(1L);

    assertThat(round.getStatus()).isEqualTo(MultiRoundEntity.Status.IN_PROGRESS);
    assertThat(interview.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.IN_PROGRESS);
    assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    assertThat(interview.getCurrentRoundNo()).isEqualTo(1);
    verifyNoInteractions(agentOrchestrator);
  }

  @Test @DisplayName("题目全部答完后才能显式结束轮次")
  void shouldFinishRoundAfterAllQuestionsAnswered() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(2); round.setQuestionLimit(1); round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity(); question.setStatus(MultiRoundQuestionEntity.Status.ANSWERED);
    when(roundRepository.findById(2L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(2L)).thenReturn(List.of(question));
    when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roundReportService.build(round, List.of(question))).thenReturn("{\"answeredQuestionCount\":1}");

    new MultiRoundLifecycleService(roundRepository, questionRepository, interviewRepository, roundReportService,
        questionGenerationService, agentOrchestrator, planningContextFactory, workflowService,
        finalReportService).finish(2L);

    assertThat(round.getStatus()).isEqualTo(MultiRoundEntity.Status.COMPLETED);
    assertThat(interview.getStatus()).isEqualTo(MultiRoundInterviewEntity.Status.ROUND_COMPLETED);
    assertThat(round.getReportJson()).contains("answeredQuestionCount");
  }

  @Test @DisplayName("算法轮必须在三题均静态评审完成后才能结束")
  void shouldRequireAllAlgorithmQuestionsToBeEvaluated() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview); round.setRoundNo(1); round.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    round.setQuestionLimit(3); round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    MultiRoundQuestionEntity first = new MultiRoundQuestionEntity(); first.setStatus(MultiRoundQuestionEntity.Status.EVALUATED);
    MultiRoundQuestionEntity second = new MultiRoundQuestionEntity(); second.setStatus(MultiRoundQuestionEntity.Status.EVALUATED);
    MultiRoundQuestionEntity third = new MultiRoundQuestionEntity(); third.setStatus(MultiRoundQuestionEntity.Status.ANSWERED);
    when(roundRepository.findById(3L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(3L)).thenReturn(List.of(first, second, third));

    assertThatThrownBy(() -> new MultiRoundLifecycleService(roundRepository, questionRepository,
        interviewRepository, roundReportService, questionGenerationService, agentOrchestrator,
        planningContextFactory, workflowService, finalReportService).finish(3L))
        .hasMessageContaining("静态评审");
  }
}
