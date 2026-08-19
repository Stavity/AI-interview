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

  @Transactional
  public MultiRoundEntity start(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    MultiRoundInterviewEntity interview = round.getInterview();
    if (round.getRoundNo() != interview.getCurrentRoundNo() + 1) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不能跳过未完成的面试轮次");
    }
    InterviewPlanningContext context = planningContextFactory.create(interview, round);
    InterviewPlan plan = agentOrchestrator.plan(interview, "ROUND_START", context);
    if (plan.roundNo() != round.getRoundNo() || !plan.roundType().equals(round.getRoundType().name())) {
      plan = new InterviewPlan(round.getRoundNo(), round.getRoundType().name(), 0,
          java.util.List.of(round.getRoundType().name()), 1);
    }
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    if (round.getStartedAt() == null) round.setStartedAt(LocalDateTime.now());
    interview.setCurrentRoundNo(round.getRoundNo());
    interview.setStatus(MultiRoundInterviewEntity.Status.IN_PROGRESS);
    if (interview.getStartedAt() == null) interview.setStartedAt(LocalDateTime.now());
    roundRepository.save(round);
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
    round.setStatus(MultiRoundEntity.Status.COMPLETED);
    round.setCompletedAt(LocalDateTime.now());
    round.setReportJson(roundReportService.build(round, questions));
    MultiRoundInterviewEntity interview = round.getInterview();
    if (round.getRoundType() == MultiRoundEntity.Type.HR) {
      interview.setStatus(MultiRoundInterviewEntity.Status.COMPLETED);
      interview.setCompletedAt(LocalDateTime.now());
    } else {
      interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_COMPLETED);
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
      return null;
    }
    interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_READY);
    interviewRepository.save(interview);
    return rounds.stream()
        .filter(r -> r.getRoundNo() == interview.getCurrentRoundNo() + 1)
        .findFirst()
        .orElse(null);
  }

  private MultiRoundEntity getRound(Long roundId) {
    return roundRepository.findById(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
  }

}
