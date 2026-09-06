package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.ai.agent.InterviewAgentOrchestrator;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.service.MultiRoundQuestionGenerationService;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MultiRoundLifecycleService {
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  private final MultiRoundInterviewRepository interviewRepository;
  private final MultiRoundRoundReportService roundReportService;
  private final MultiRoundQuestionGenerationService questionGenerationService;
  private final InterviewAgentOrchestrator agentOrchestrator;
  private final MultiRoundPlanningContextFactory planningContextFactory;
  private final MultiRoundWorkflowService workflowService;
  private final MultiRoundFinalReportService finalReportService;

  @Transactional
  public MultiRoundEntity start(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    MultiRoundInterviewEntity interview = round.getInterview();
    if (round.getRoundNo() != interview.getCurrentRoundNo() + 1) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不能跳过未完成的面试轮次");
    }
    InterviewPlanningContext context = planningContextFactory.create(interview, round);
    InterviewPlan plan = workflowService.usesStaticExecutor(interview)
        ? staticPlan(round, context) : agentOrchestrator.plan(interview, "ROUND_START", context);
    if (plan.roundNo() != round.getRoundNo() || !plan.roundType().equals(round.getRoundType().name())) {
      plan = new InterviewPlan(round.getRoundNo(), round.getRoundType().name(), 0,
          java.util.List.of(round.getRoundType().name()), 1);
    }
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    if (round.getStartedAt() == null) round.setStartedAt(LocalDateTime.now());
    interview.setCurrentRoundNo(round.getRoundNo());
    interview.setStatus(MultiRoundInterviewEntity.Status.IN_PROGRESS);
    interview.getInterviewSession().setStatus(
        InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    if (interview.getStartedAt() == null) interview.setStartedAt(LocalDateTime.now());
    roundRepository.save(round);
    workflowService.roundStarted(interview, round);
    // Auto-generate questions on first round entry (M-03)
    questionGenerationService.ensureQuestionsGenerated(round, plan, context);
    return round;
  }

  @Transactional
  public MultiRoundEntity finish(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次尚未启动");
    }
    var questions = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId);
    if (questions.size() < round.getQuestionLimit() || questions.stream().anyMatch(question -> question.getStatus() == MultiRoundQuestionEntity.Status.PENDING)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前轮次仍有未完成题目");
    }
    if (round.getRoundType() == MultiRoundEntity.Type.ALGORITHM
        && questions.stream().anyMatch(question -> question.getStatus() != MultiRoundQuestionEntity.Status.EVALUATED)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "算法轮题目尚未完成静态评审");
    }
    round.setStatus(MultiRoundEntity.Status.COMPLETED);
    round.setCompletedAt(LocalDateTime.now());
    round.setReportJson(roundReportService.build(round, questions));
    MultiRoundInterviewEntity interview = round.getInterview();
    if (round.getRoundType() == MultiRoundEntity.Type.HR) {
      interview.setStatus(MultiRoundInterviewEntity.Status.COMPLETED);
      interview.setCompletedAt(LocalDateTime.now());
      interview.getInterviewSession().setStatus(
          InterviewSessionEntity.SessionStatus.COMPLETED);
    } else {
      interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_COMPLETED);
    }
    workflowService.roundCompleted(interview, round);
    if (interview.getStatus() == MultiRoundInterviewEntity.Status.COMPLETED) {
      workflowService.completed(interview);
      finalReportService.generate(interview);
    }
    return roundRepository.save(round);
  }


  /**
   * Validates that the current round is completed and advances interview status
   * to allow the next round to start. Returns the next pending round, or null if
   * the interview is fully completed.
   */
  @Transactional
  public MultiRoundEntity advanceToNextRound(Long interviewId) {
    MultiRoundInterviewEntity interview = interviewRepository.findById(interviewId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试不存在: " + interviewId));
    var rounds = roundRepository.findByInterviewIdOrderByRoundNoAsc(interviewId);
    MultiRoundEntity currentRound = rounds.stream()
        .filter(r -> r.getRoundNo() == interview.getCurrentRoundNo())
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.ROUND_STATE_INVALID, "当前轮次不存在"));
    if (currentRound.getStatus() != MultiRoundEntity.Status.COMPLETED) {
      throw new BusinessException(ErrorCode.ROUND_STATE_INVALID, "当前轮次尚未完成，不能进入下一轮");
    }
    if (currentRound.getRoundType() == MultiRoundEntity.Type.HR) {
      interview.setStatus(MultiRoundInterviewEntity.Status.COMPLETED);
      interviewRepository.save(interview);
      workflowService.completed(interview);
      finalReportService.generate(interview);
      return null;
    }
    interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_READY);
    interviewRepository.save(interview);
    MultiRoundEntity next = rounds.stream()
        .filter(r -> r.getRoundNo() == interview.getCurrentRoundNo() + 1)
        .findFirst()
        .orElse(null);
    if (next != null) workflowService.nextRoundReady(interview, next.getRoundNo());
    return next;
  }

  /** Candidate actions are the only trigger; the state machine owns completion and next-round start. */
  @Transactional
  public MultiRoundEntity progressAfterCandidateAction(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) return null;
    var questions = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId);
    boolean ready = questions.size() >= round.getQuestionLimit()
        && questions.stream().noneMatch(question -> question.getStatus() == MultiRoundQuestionEntity.Status.PENDING)
        && (round.getRoundType() != MultiRoundEntity.Type.ALGORITHM
            || questions.stream().allMatch(question -> question.getStatus() == MultiRoundQuestionEntity.Status.EVALUATED));
    if (!ready) return null;
    finish(roundId);
    MultiRoundEntity next = advanceToNextRound(round.getInterview().getId());
    if (next != null) start(next.getId());
    return next;
  }

  private InterviewPlan staticPlan(MultiRoundEntity round, InterviewPlanningContext context) {
    String category = context.allowedCategoryKeys() == null || context.allowedCategoryKeys().isEmpty()
        ? "general" : context.allowedCategoryKeys().getFirst();
    return new InterviewPlan(round.getRoundNo(), round.getRoundType().name(), 0, java.util.List.of(category), 1);
  }

  private MultiRoundEntity getRound(Long roundId) {
    return roundRepository.findById(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
  }

}
