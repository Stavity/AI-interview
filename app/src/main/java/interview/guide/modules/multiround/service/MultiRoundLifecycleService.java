package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轮次生命周期。
 * <p>
 * 启动流程刻意拆成 {@link #preflightStart} / {@link #commitStart} 两段，中间留给 LLM：
 * 旧实现把两次 LLM 调用包在同一个 {@code @Transactional} 里，数据库连接被占 30-60s，
 * 池默认 10 条时约 10 个并发即耗尽。
 * <p>
 * 注意这里不采用「先提交 IN_PROGRESS、再异步补题」的三段式：那会公开一个
 * IN_PROGRESS 但 0 题的中间态，此时 addQuestion 能插入 sequence_no=1，
 * 随后补题逻辑判定已有题目而提前返回，轮次永久停在题目不足、finish() 恒失败的状态。
 * 现在的形状保证：要么 PENDING（什么都没写），要么 IN_PROGRESS 且题目满额，无中间态。
 */
@Service
@RequiredArgsConstructor
public class MultiRoundLifecycleService {
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  private final MultiRoundInterviewRepository interviewRepository;
  private final MultiRoundRoundReportService roundReportService;
  private final MultiRoundQuestionGenerationService questionGenerationService;
  private final MultiRoundPlanningContextFactory planningContextFactory;

  /** 启动前置：完成全部校验并取出 LLM 所需上下文。只读短事务。 */
  @Transactional(readOnly = true)
  public StartPreflight preflightStart(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    MultiRoundInterviewEntity interview = round.getInterview();
    boolean alreadyCompleted = round.getStatus() == MultiRoundEntity.Status.COMPLETED;
    if (!alreadyCompleted) {
      validateStartable(round, interview);
    }
    return new StartPreflight(
        roundId,
        interview.getId(),
        interview.getInterviewSession().getSessionId(),
        alreadyCompleted,
        alreadyCompleted ? null : planningContextFactory.create(interview, round));
  }

  /**
   * 启动落库：悲观锁重载轮次、重新校验（防止 LLM 窗口期间状态被并发改动）、
   * 写入轮次状态与题目。单个短事务，无 LLM 调用。
   */
  @Transactional
  public MultiRoundEntity commitStart(
      Long roundId, InterviewPlan plan, List<MultiRoundQuestionGenerationService.GeneratedQuestion> drafted) {
    MultiRoundEntity round = roundRepository.findByIdForUpdate(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    MultiRoundInterviewEntity interview = round.getInterview();
    validateStartable(round, interview);

    int existing = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId).size();
    int shortfall = round.getQuestionLimit() - existing;
    if (shortfall > 0) {
      List<MultiRoundQuestionGenerationService.GeneratedQuestion> usable =
          drafted.size() > shortfall ? drafted.subList(0, shortfall) : drafted;
      questionGenerationService.persist(round, plan, usable, existing);
    }

    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    if (round.getStartedAt() == null) round.setStartedAt(LocalDateTime.now());
    interview.setCurrentRoundNo(round.getRoundNo());
    interview.setStatus(MultiRoundInterviewEntity.Status.IN_PROGRESS);
    if (interview.getStartedAt() == null) interview.setStartedAt(LocalDateTime.now());
    return roundRepository.save(round);
  }

  /**
   * 轮次可启动校验。
   * <ul>
   *   <li>允许重入：同一轮已在进行中时可重试（LLM 失败后补齐题目）。
   *       旧实现不可重入——currentRoundNo 已被推进，重试恒被守卫拒绝，只能改库恢复。</li>
   *   <li>不允许在上一轮未完成时启动下一轮。旧实现仅校验
   *       {@code roundNo != currentRoundNo + 1}：启动第 1 轮后 currentRoundNo=1，
   *       此时启动第 2 轮 {@code 2 != 2} 为假直接放行，两轮同时 IN_PROGRESS。</li>
   * </ul>
   */
  private void validateStartable(MultiRoundEntity round, MultiRoundInterviewEntity interview) {
    int roundNo = round.getRoundNo();
    int currentRoundNo = interview.getCurrentRoundNo();
    if (roundNo == currentRoundNo && round.getStatus() == MultiRoundEntity.Status.IN_PROGRESS) {
      return;
    }
    if (roundNo != currentRoundNo + 1) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不能跳过未完成的面试轮次");
    }
    if (roundNo > 1) {
      MultiRoundEntity previous = roundRepository
          .findByInterviewIdOrderByRoundNoAsc(interview.getId()).stream()
          .filter(item -> item.getRoundNo() == roundNo - 1)
          .findFirst()
          .orElseThrow(() -> new BusinessException(ErrorCode.ROUND_STATE_INVALID, "上一轮次不存在"));
      if (previous.getStatus() != MultiRoundEntity.Status.COMPLETED) {
        throw new BusinessException(ErrorCode.ROUND_STATE_INVALID, "上一轮次尚未完成，不能启动本轮");
      }
    }
  }

  @Transactional
  public MultiRoundEntity finish(Long roundId) {
    MultiRoundEntity round = roundRepository.findByIdForUpdate(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次尚未启动");
    }
    var questions = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId);
    if (questions.size() < round.getQuestionLimit()
        || questions.stream().anyMatch(question -> question.getStatus() == MultiRoundQuestionEntity.Status.PENDING)) {
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
   * 校验当前轮次已完成并推进面试状态，返回下一轮；面试全部完成时返回 null。
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

  /** 启动前置结果。{@code alreadyCompleted} 为 true 时无需调用 LLM。 */
  public record StartPreflight(
      Long roundId,
      Long interviewId,
      String sessionId,
      boolean alreadyCompleted,
      InterviewPlanningContext context) {}
}
