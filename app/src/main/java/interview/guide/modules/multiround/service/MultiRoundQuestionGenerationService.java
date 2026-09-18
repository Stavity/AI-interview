package interview.guide.modules.multiround.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轮次题目生成。
 * <p>
 * 分为两个阶段，刻意分离以保证：LLM 调用不占用数据库连接，且失败时不产生部分写入。
 * <ul>
 *   <li>{@link #draft} —— 无事务。调用 LLM，清洗并钳制所有字段到 DDL 上限，内部降级，
 *       保证返回恰好 {@code count} 条可直接落库的草稿。绝不抛出。</li>
 *   <li>{@link #persist} —— 短事务。唯一的写入方，单次 {@code saveAll}，无 try/catch、无降级。</li>
 * </ul>
 * 旧实现把降级逻辑放在 try 之外：循环内 save 失败后 PostgreSQL 事务已进入 aborted 状态
 * （25P02），降级写入必然连带失败，且 sequence_no 与已插入行冲突
 * （multi_round_questions_unique_sequence），导致整个事务回滚、轮次永久不可用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundQuestionGenerationService {

  /** question_type VARCHAR(32) —— 见 V20260822__add_multi_round_schema.sql */
  private static final int MAX_QUESTION_TYPE_LENGTH = 32;
  /** category_key VARCHAR(128) */
  private static final int MAX_CATEGORY_KEY_LENGTH = 128;
  /** 与 {@link #defaultType} 保持同一套词表，避免 LLM 自由发挥导致报告分桶散乱 */
  private static final Set<String> ALLOWED_QUESTION_TYPES =
      Set.of("ALGORITHM", "TECHNICAL", "SYSTEM_DESIGN", "BEHAVIORAL");

  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker outputInvoker;
  private final PromptSanitizer promptSanitizer;
  private final MultiRoundQuestionRepository questionRepository;

  /**
   * 确保轮次拥有满额题目。已满额则直接返回；不足（含 0 条与部分生成）则补齐缺口。
   * <p>
   * 本方法不带事务：LLM 在事务外执行，仅 {@link #persist} 开短事务。
   */
  public List<MultiRoundQuestionEntity> ensureQuestionsGenerated(MultiRoundEntity round) {
    return ensureQuestionsGenerated(round, null, null);
  }

  public List<MultiRoundQuestionEntity> ensureQuestionsGenerated(
      MultiRoundEntity round, InterviewPlan plan, InterviewPlanningContext context) {
    int limit = round.getQuestionLimit();
    List<MultiRoundQuestionEntity> existing =
        questionRepository.findByRoundIdOrderBySequenceNoAsc(round.getId());
    // 旧实现是 !existing.isEmpty()：部分生成（k < limit）会被当作已完成，
    // 之后 finish() 因 size() < questionLimit 永远抛错，轮次卡死。
    if (existing.size() >= limit) {
      return existing;
    }

    InterviewPlan effectivePlan = plan != null ? plan : defaultPlan(round);
    List<GeneratedQuestion> drafted = draft(round, effectivePlan, context, limit - existing.size());
    persist(round, effectivePlan, drafted, existing.size());
    return questionRepository.findByRoundIdOrderBySequenceNoAsc(round.getId());
  }

  /**
   * 生成题目草稿。无事务，可安全承载 LLM 调用。
   * <p>
   * 保证：返回恰好 {@code count} 条，每条的 questionType / categoryKey 均已钳制到 DDL 上限，
   * question 非空。LLM 失败、返回空列表或返回数量不足时，用确定性题目补齐。
   */
  public List<GeneratedQuestion> draft(
      MultiRoundEntity round, InterviewPlan plan, InterviewPlanningContext context, int count) {
    List<String> categoryKeys = safeCategoryKeys(plan, round);
    List<GeneratedQuestion> drafts = new ArrayList<>(count);

    try {
      List<GeneratedQuestion> generated =
          invokeGeneration(round.getInterview(), round, plan, context, count);
      for (GeneratedQuestion candidate : generated) {
        if (drafts.size() >= count) {
          break;
        }
        String question = normalizeText(candidate.question());
        if (question == null) {
          // question 列为 TEXT NOT NULL，LLM 返回空会直接违反非空约束
          continue;
        }
        drafts.add(new GeneratedQuestion(
            resolveQuestionType(candidate.questionType(), round),
            resolveCategoryKey(candidate.categoryKey(), categoryKeys),
            question,
            normalizeText(candidate.referenceAnswer())));
      }
    } catch (Exception error) {
      log.warn("多轮题目 AI 生成失败，降级为确定性题目: roundId={}, error={}",
          round.getId(), error.getMessage());
      drafts.clear();
    }

    // 补齐缺口：LLM 可能成功但返回空列表或数量不足（旧实现此时不触发降级，轮次留 0 题后永久卡死）
    if (drafts.size() < count) {
      log.info("题目草稿不足，用确定性题目补齐: roundId={}, drafted={}, expected={}",
          round.getId(), drafts.size(), count);
      while (drafts.size() < count) {
        drafts.add(new GeneratedQuestion(
            defaultType(round),
            categoryKeys.getFirst(),
            deterministicQuestion(round, context, drafts.size() + 1),
            "参考答案将在评估阶段生成。"));
      }
    }
    return drafts;
  }

  /**
   * 落库。唯一的写入方，单次 saveAll，无降级逻辑。
   *
   * @param startSequenceNo 已有题目数；新题目的 sequence_no 从此值 +1 开始，避免与已有行冲突
   */
  @Transactional
  public List<MultiRoundQuestionEntity> persist(
      MultiRoundEntity round, InterviewPlan plan, List<GeneratedQuestion> drafted, int startSequenceNo) {
    if (drafted.isEmpty()) {
      return List.of();
    }
    List<MultiRoundQuestionEntity> entities = new ArrayList<>(drafted.size());
    for (int i = 0; i < drafted.size(); i++) {
      GeneratedQuestion draft = drafted.get(i);
      MultiRoundQuestionEntity entity = new MultiRoundQuestionEntity();
      entity.setRound(round);
      entity.setSequenceNo(startSequenceNo + i + 1);
      entity.setQuestionType(draft.questionType());
      entity.setCategoryKey(draft.categoryKey());
      entity.setQuestion(draft.question());
      entity.setReferenceAnswer(draft.referenceAnswer());
      entities.add(entity);
    }
    return questionRepository.saveAll(entities);
  }

  // ==================== 字段清洗 ====================

  /** LLM 返回的类型必须落在词表内，否则回退轮次默认类型。同时保证不超过 VARCHAR(32)。 */
  private String resolveQuestionType(String candidate, MultiRoundEntity round) {
    if (candidate == null) {
      return defaultType(round);
    }
    String normalized = candidate.trim().toUpperCase();
    if (normalized.length() > MAX_QUESTION_TYPE_LENGTH || !ALLOWED_QUESTION_TYPES.contains(normalized)) {
      return defaultType(round);
    }
    return normalized;
  }

  /** 必须落在计划允许的分类内，且不超过 VARCHAR(128)。 */
  private String resolveCategoryKey(String candidate, List<String> categoryKeys) {
    if (candidate != null) {
      String normalized = candidate.trim();
      if (!normalized.isEmpty()
          && normalized.length() <= MAX_CATEGORY_KEY_LENGTH
          && categoryKeys.contains(normalized)) {
        return normalized;
      }
    }
    return categoryKeys.getFirst();
  }

  /**
   * 计划的分类列表兜底。
   * <p>
   * 旧实现直接 {@code plan.categoryKeys().getFirst()}：计划分类为空时在 try 内抛
   * NoSuchElementException，降级分支里再抛一次并逃出 catch。
   * 这里保证列表非空且每项都在 VARCHAR(128) 内。
   */
  private List<String> safeCategoryKeys(InterviewPlan plan, MultiRoundEntity round) {
    List<String> fallback = List.of(round.getRoundType().name());
    if (plan == null || plan.categoryKeys() == null) {
      return fallback;
    }
    List<String> usable = plan.categoryKeys().stream()
        .filter(key -> key != null && !key.isBlank())
        .map(String::trim)
        .filter(key -> key.length() <= MAX_CATEGORY_KEY_LENGTH)
        .toList();
    return usable.isEmpty() ? fallback : usable;
  }

  private String normalizeText(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private InterviewPlan defaultPlan(MultiRoundEntity round) {
    return new InterviewPlan(round.getRoundNo(), round.getRoundType().name(), 0,
        List.of(round.getRoundType().name()), 1);
  }

  // ==================== LLM ====================

  private List<GeneratedQuestion> invokeGeneration(
      MultiRoundInterviewEntity interview, MultiRoundEntity round, InterviewPlan plan,
      InterviewPlanningContext context, int count) {
    var converter = new BeanOutputConverter<>(GeneratedQuestionList.class);
    String systemPrompt = """
        你是面试出题器。根据轮次类型生成 %d 道结构化面试题目。
        严禁调用工具、执行代码或访问外部资源。只返回 JSON 格式的题目列表。
        每道题必须包含 questionType、categoryKey、question 和 referenceAnswer 字段。
        questionType 只能取以下之一：%s。
        轮次类型：%s；允许分类：%s。
        """.formatted(count, String.join(",", ALLOWED_QUESTION_TYPES), round.getRoundType().name(),
        String.join(",", safeCategoryKeys(plan, round))) + converter.getFormat();

    String userPrompt = (context != null ? context.asPrompt() : """
        简历快照：%s
        岗位 JD：%s
        面试技能：%s；难度：%s。
        """.formatted(interview.getResumeSnapshot(), interview.getJdSnapshot(),
        interview.getSkillId(), interview.getDifficulty())) + "\n请为第 %d 轮（%s）生成 %d 道面试题目，并只使用允许分类。"
        .formatted(round.getRoundNo(), round.getRoundType().name(), count);

    GeneratedQuestionList result = outputInvoker.invoke(
        registry.getPlainChatClient(interview.getProviderId()),
        systemPrompt,
        promptSanitizer.wrapWithDelimiters("multi-round-question", userPrompt),
        converter,
        ErrorCode.AI_SERVICE_ERROR,
        "多轮题目生成失败: ",
        "[multi-round-question-gen] ",
        LoggerFactory.getLogger(MultiRoundQuestionGenerationService.class)
    );
    return result != null && result.questions() != null ? result.questions() : List.of();
  }

  private String defaultType(MultiRoundEntity round) {
    return switch (round.getRoundType()) {
      case ALGORITHM -> "ALGORITHM";
      case TECHNICAL_1 -> "TECHNICAL";
      case TECHNICAL_2 -> "SYSTEM_DESIGN";
      case HR -> "BEHAVIORAL";
    };
  }

  private String deterministicQuestion(MultiRoundEntity round, InterviewPlanningContext context, int index) {
    String focus = context != null && context.skillId() != null && !context.skillId().isBlank()
        ? context.skillId() : round.getRoundType().name();
    return switch (round.getRoundType()) {
      case ALGORITHM -> "请围绕 %s 实现一个算法函数，并分析时间与空间复杂度。".formatted(focus);
      case TECHNICAL_1 -> "请结合简历中的 %s 经历，解释相关技术原理与实际取舍。".formatted(focus);
      case TECHNICAL_2 -> "请围绕 %s 设计一个具备扩展性和高可用能力的系统方案。".formatted(focus);
      case HR -> "请结合简历中与 %s 相关的经历，描述一次挑战及解决方式。".formatted(focus);
    };
  }

  public record GeneratedQuestion(String questionType, String categoryKey, String question, String referenceAnswer) {}
  public record GeneratedQuestionList(List<GeneratedQuestion> questions) {}
}
