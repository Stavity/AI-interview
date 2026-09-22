package interview.guide.modules.multiround.service;

import interview.guide.common.ai.agent.InterviewAgentOrchestrator;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.model.MultiRoundIdempotencyEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多轮面试写操作编排。
 * <p>
 * 幂等策略采用与 {@code InterviewSessionService.createSession} 相同的成熟形状：
 * <b>分布式锁串行化 + 锁内复查 + 唯一约束冲突回读</b>。方法本身不带事务，
 * 业务调用各自开短事务；幂等记录与业务写入在同一事务内提交。
 * <p>
 * 此处刻意不采用「先插 PENDING 占位再执行」的 claim 方案：占位提交后若进程崩溃，
 * 该幂等键将永久返回「处理中」，把瞬时重复放大成不可恢复的死锁；
 * 且占位需 REQUIRES_NEW 隔离污染事务，会在 LLM 仍持有外层连接时占用第二条连接，
 * 默认 10 条池容量下约 10 并发即自死锁。Redis 租约天然充当 TTL，孤儿自愈。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundOperationService {
  private static final long LOCK_WAIT_SECONDS = 20;
  private static final long LOCK_LEASE_SECONDS = 300;

  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  private final AlgorithmSubmissionRepository submissionRepository;
  private final MultiRoundIdempotencyService idempotencyService;
  private final MultiRoundLifecycleService lifecycleService;
  private final MultiRoundInterviewService interviewService;
  private final MultiRoundQuestionService questionService;
  private final AlgorithmSubmissionService submissionService;
  private final MultiRoundQuestionGenerationService questionGenerationService;
  private final InterviewAgentOrchestrator agentOrchestrator;
  private final RedisService redisService;
  private final ObjectMapper objectMapper;

  /**
   * 启动轮次：前置校验（只读短事务）→ 锁内复查 → 两次 LLM（无事务）→ 落库（单个短事务）。
   */
  public MultiRoundEntity startRound(Long roundId, String key) {
    requireKey(key);
    MultiRoundLifecycleService.StartPreflight preflight = lifecycleService.preflightStart(roundId);
    requireResumeBound(interviewService.getBySessionId(preflight.sessionId()));
    if (preflight.alreadyCompleted()) {
      return getRound(roundId);
    }
    String operation = "START:" + roundId;
    return executeIdempotent(preflight.sessionId(), operation, key,
        record -> getRound(roundId),
        () -> {
          InterviewPlan plan = resolvePlan(preflight, roundId);
          List<MultiRoundQuestionGenerationService.GeneratedQuestion> drafted =
              draftForRound(roundId, plan, preflight);
          MultiRoundEntity started = lifecycleService.commitStart(roundId, plan, drafted);
          idempotencyService.record(preflight.sessionId(), operation, key,
              started.getInterview(), "ROUND", roundId);
          return started;
        });
  }

  /** LLM 计划生成。计划与轮次不符或分类为空时回退到确定性计划。 */
  private InterviewPlan resolvePlan(MultiRoundLifecycleService.StartPreflight preflight, Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(preflight.sessionId());
    InterviewPlan plan = agentOrchestrator.plan(interview, "ROUND_START", preflight.context());
    boolean usable = plan != null
        && plan.roundNo() == round.getRoundNo()
        && plan.roundType().equals(round.getRoundType().name())
        && plan.categoryKeys() != null
        && !plan.categoryKeys().isEmpty();
    if (usable) {
      return plan;
    }
    return new InterviewPlan(round.getRoundNo(), round.getRoundType().name(), 0,
        List.of(round.getRoundType().name()), 1);
  }

  private List<MultiRoundQuestionGenerationService.GeneratedQuestion> draftForRound(
      Long roundId, InterviewPlan plan, MultiRoundLifecycleService.StartPreflight preflight) {
    MultiRoundEntity round = getRound(roundId);
    int existing = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId).size();
    int shortfall = Math.max(round.getQuestionLimit() - existing, 0);
    if (shortfall == 0) {
      return List.of();
    }
    return questionGenerationService.draft(round, plan, preflight.context(), shortfall);
  }

  public MultiRoundEntity finishRound(Long roundId, String key) {
    requireKey(key);
    MultiRoundEntity round = getRound(roundId);
    requireResumeBound(round.getInterview());
    String sessionId = sessionId(round);
    String operation = "FINISH:" + roundId;
    return executeIdempotent(sessionId, operation, key,
        record -> getRound(roundId),
        () -> {
          MultiRoundEntity finished = lifecycleService.finish(roundId);
          idempotencyService.record(sessionId, operation, key, finished.getInterview(), "ROUND", roundId);
          return finished;
        });
  }

  public MultiRoundQuestionEntity addQuestion(
      Long roundId, String key, String type, String category, String text, String reference) {
    requireKey(key);
    MultiRoundEntity round = getRound(roundId);
    requireResumeBound(round.getInterview());
    String sessionId = sessionId(round);
    String operation = "QUESTION:" + roundId;
    return executeIdempotent(sessionId, operation, key,
        record -> requireResource(record, questionRepository::findById, "幂等题目结果不存在"),
        () -> {
          MultiRoundQuestionEntity question =
              questionService.addQuestion(roundId, type, category, text, reference);
          idempotencyService.record(sessionId, operation, key, round.getInterview(), "QUESTION", question.getId());
          return question;
        });
  }

  public void answer(Long questionId, String key, String answer) {
    requireKey(key);
    MultiRoundQuestionEntity question = getQuestion(questionId);
    requireResumeBound(question.getRound().getInterview());
    String sessionId = sessionId(question.getRound());
    String operation = "ANSWER:" + questionId;
    boolean accepted = executeIdempotent(sessionId, operation, key,
        record -> false,
        () -> {
          questionService.answer(questionId, answer);
          idempotencyService.record(sessionId, operation, key,
              question.getRound().getInterview(), "QUESTION", questionId);
          return true;
        });
    if (accepted) progressAfterCandidateAction(question.getRound().getId());
  }

  /**
   * Server-owned progression hook. A user action can only complete the current node once every
   * persisted question is complete; transition and next-node materialization retain their own
   * idempotency records so a browser retry cannot duplicate a question set.
   */
  public void progressAfterCandidateAction(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) return;
    List<MultiRoundQuestionEntity> questions = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId);
    if (questions.size() < round.getQuestionLimit()
        || questions.stream().anyMatch(question -> question.getStatus() == MultiRoundQuestionEntity.Status.PENDING)) {
      return;
    }
    String sessionId = sessionId(round);
    try {
      finishRound(roundId, "system-auto-finish:" + roundId);
      MultiRoundEntity next = nextRound(sessionId, "system-auto-next:" + roundId);
      if (next != null) {
        startRound(next.getId(), "system-auto-start:" + next.getId());
      }
    } catch (BusinessException error) {
      // A node may be completed concurrently. The next user action or recovery read can safely
      // retry because every automatic step has a stable idempotency key.
      log.info("自动推进暂未完成: roundId={}, code={}, message={}", roundId, error.getCode(), error.getMessage());
    }
  }

  public AlgorithmSubmissionEntity submitAlgorithm(Long questionId, String key, String language, String sourceCode) {
    requireKey(key);
    MultiRoundQuestionEntity question = getQuestion(questionId);
    requireResumeBound(question.getRound().getInterview());
    String sessionId = sessionId(question.getRound());
    String operation = "ALGORITHM_SUBMIT:" + questionId;
    return executeIdempotent(sessionId, operation, key,
        record -> requireResource(record, submissionRepository::findById, "幂等提交结果不存在"),
        () -> {
          AlgorithmSubmissionEntity submission = submitWithConflictRetry(questionId, language, sourceCode);
          idempotencyService.record(sessionId, operation, key,
              question.getRound().getInterview(), "ALGORITHM_SUBMISSION", submission.getId());
          return submission;
        });
  }

  /**
   * algorithm_submissions_question_unique 限制每题至多一条提交，而幂等键是按
   * (question, key) 划分的：同一题用不同幂等键并发提交时两侧都可能判定「尚无提交」而同时插入。
   * 此处冲突后重试一次——第二次 submit 会查到既有行转为更新。
   * 捕获点在 submit() 的事务边界之外，故可安全地在新事务中重试。
   */
  private AlgorithmSubmissionEntity submitWithConflictRetry(Long questionId, String language, String sourceCode) {
    try {
      return submissionService.submit(questionId, language, sourceCode);
    } catch (DataIntegrityViolationException conflict) {
      log.info("算法提交唯一约束冲突，转为更新既有提交: questionId={}", questionId);
      return submissionService.submit(questionId, language, sourceCode);
    }
  }

  /**
   * 进入下一轮。
   * <p>
   * 幂等记录改为落 {@code ROUND} + 下一轮 id：旧实现记的是 INTERVIEW + interview.id
   * （与实际返回值不符），且重放时直接返回 null，使「面试已结束」与「重复请求」
   * 在前端无法区分。现在 resourceId 为空才表示确实没有下一轮。
   */
  public MultiRoundEntity nextRound(String sessionId, String key) {
    requireKey(key);
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    requireResumeBound(interview);
    String operation = "NEXT_ROUND";
    return executeIdempotent(sessionId, operation, key,
        record -> record.getResourceId() == null ? null
            : roundRepository.findById(record.getResourceId()).orElse(null),
        () -> {
          MultiRoundEntity next = lifecycleService.advanceToNextRound(interview.getId());
          idempotencyService.record(sessionId, operation, key, interview,
              "ROUND", next != null ? next.getId() : null);
          return next;
        });
  }

  @Transactional(readOnly = true)
  public MultiRoundRoundReportService.RoundReport getRoundReport(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    requireResumeBound(round.getInterview());
    if (round.getReportJson() == null) {
      return emptyRoundReport(round);
    }
    try {
      return objectMapper.readValue(round.getReportJson(), MultiRoundRoundReportService.RoundReport.class);
    } catch (Exception e) {
      log.warn("轮次报告反序列化失败: roundId={}, error={}", roundId, e.getMessage());
      return emptyRoundReport(round);
    }
  }

  private MultiRoundRoundReportService.RoundReport emptyRoundReport(MultiRoundEntity round) {
    return new MultiRoundRoundReportService.RoundReport(
        round.getRoundNo(), round.getRoundType().name(), 0, 0, java.util.Map.of());
  }

  // ==================== 幂等编排 ====================

  /**
   * 幂等执行。
   * <ol>
   *   <li>锁外快速查一次已有记录，命中则重放</li>
   *   <li>按 sessionId+operation+key 取分布式锁，串行化同键并发</li>
   *   <li>锁内复查（第一个请求可能刚提交）</li>
   *   <li>执行业务；唯一约束冲突时回读已有记录重放（Redis 不可用时的持久兜底）</li>
   * </ol>
   */
  private <T> T executeIdempotent(String sessionId, String operation, String key,
                                  java.util.function.Function<MultiRoundIdempotencyEntity, T> replay,
                                  Supplier<T> execute) {
    Optional<MultiRoundIdempotencyEntity> existing = idempotencyService.find(sessionId, operation, key);
    if (existing.isPresent()) {
      return replay.apply(existing.get());
    }
    String lockKey = "multiround:" + sessionId + ":" + operation + ":" + key;
    return redisService.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS,
        () -> {
          Optional<MultiRoundIdempotencyEntity> underLock =
              idempotencyService.find(sessionId, operation, key);
          if (underLock.isPresent()) {
            return replay.apply(underLock.get());
          }
          try {
            return execute.get();
          } catch (DataIntegrityViolationException conflict) {
            // 唯一约束兜底：锁失效或被绕过时仍保证至多一次
            MultiRoundIdempotencyEntity recorded = idempotencyService.find(sessionId, operation, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.MULTI_ROUND_OPERATION_IN_PROGRESS,
                    "操作冲突，请稍后重试"));
            log.info("幂等冲突回读: sessionId={}, operation={}", sessionId, operation);
            return replay.apply(recorded);
          }
        });
  }

  private <T> T requireResource(MultiRoundIdempotencyEntity record,
                                java.util.function.Function<Long, Optional<T>> finder,
                                String message) {
    if (record.getResourceId() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, message);
    }
    return finder.apply(record.getResourceId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, message));
  }

  private void requireKey(String key) {
    if (key == null || key.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能为空");
  }

  private MultiRoundEntity getRound(Long roundId) {
    return roundRepository.findById(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
  }

  private MultiRoundQuestionEntity getQuestion(Long questionId) {
    return questionRepository.findById(questionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮题目不存在: " + questionId));
  }

  private String sessionId(MultiRoundEntity round) {
    return round.getInterview().getInterviewSession().getSessionId();
  }

  public void requireResumeBound(MultiRoundInterviewEntity interview) {
    if (interview.getInterviewSession() == null || interview.getInterviewSession().getResume() == null) {
      throw new BusinessException(ErrorCode.MULTI_ROUND_RESUME_REQUIRED, "多轮面试必须绑定简历");
    }
  }
}
