package interview.guide.modules.multiround.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-generates and persists questions when a round is first started (M-03).
 * Uses structured LLM output with deterministic fallback on failure.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundQuestionGenerationService {

  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker outputInvoker;
  private final PromptSanitizer promptSanitizer;
  private final MultiRoundQuestionRepository questionRepository;

  /**
   * If the round has no questions yet, generates and persists them.
   * Returns the full question list after generation (or existing if already present).
   * On AI failure, falls back to deterministic placeholder questions.
   */
  @Transactional
  public List<MultiRoundQuestionEntity> ensureQuestionsGenerated(MultiRoundEntity round) {
    return ensureQuestionsGenerated(round, null);
  }

  @Transactional
  public List<MultiRoundQuestionEntity> ensureQuestionsGenerated(MultiRoundEntity round, InterviewPlan plan) {
    return ensureQuestionsGenerated(round, plan, null);
  }

  @Transactional
  public List<MultiRoundQuestionEntity> ensureQuestionsGenerated(
      MultiRoundEntity round, InterviewPlan plan, InterviewPlanningContext context) {
    List<MultiRoundQuestionEntity> existing = questionRepository.findByRoundIdOrderBySequenceNoAsc(round.getId());
    if (!existing.isEmpty()) {
      return existing;
    }

    if (plan == null) {
      plan = new InterviewPlan(round.getRoundNo(), round.getRoundType().name(), 0,
          List.of(round.getRoundType().name()), 1);
    }

    MultiRoundInterviewEntity interview = round.getInterview();
    int limit = round.getQuestionLimit();

    try {
      List<GeneratedQuestion> generated = invokeGeneration(interview, round, plan, context, limit);
      for (int i = 0; i < generated.size() && i < limit; i++) {
        GeneratedQuestion q = generated.get(i);
        MultiRoundQuestionEntity entity = new MultiRoundQuestionEntity();
        entity.setRound(round);
        entity.setSequenceNo(i + 1);
        entity.setQuestionType(q.questionType() != null ? q.questionType() : defaultType(round));
        entity.setCategoryKey(plan.categoryKeys().contains(q.categoryKey())
            ? q.categoryKey() : plan.categoryKeys().getFirst());
        entity.setQuestion(q.question());
        entity.setReferenceAnswer(q.referenceAnswer());
        questionRepository.save(entity);
      }
    } catch (Exception error) {
      log.warn("多轮题目 AI 生成失败，降级为确定性题目: roundId={}, error={}", round.getId(), error.getMessage());
      generateDeterministicFallback(round, plan, context, limit);
    }

    return questionRepository.findByRoundIdOrderBySequenceNoAsc(round.getId());
  }

  private List<GeneratedQuestion> invokeGeneration(
      MultiRoundInterviewEntity interview, MultiRoundEntity round, InterviewPlan plan,
      InterviewPlanningContext context, int count) {
    var converter = new BeanOutputConverter<>(GeneratedQuestionList.class);
    String systemPrompt = """
        你是面试出题器。根据轮次类型生成 %d 道结构化面试题目。
        严禁调用工具、执行代码或访问外部资源。只返回 JSON 格式的题目列表。
        每道题必须包含 questionType、categoryKey、question 和 referenceAnswer 字段。
        轮次类型：%s；允许分类：%s。
        """.formatted(count, round.getRoundType().name(), String.join(",", plan.categoryKeys())) + converter.getFormat();

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
    return result.questions() != null ? result.questions() : List.of();
  }

  private void generateDeterministicFallback(MultiRoundEntity round, InterviewPlan plan,
      InterviewPlanningContext context, int count) {
    for (int i = 0; i < count; i++) {
      MultiRoundQuestionEntity entity = new MultiRoundQuestionEntity();
      entity.setRound(round);
      entity.setSequenceNo(i + 1);
      entity.setQuestionType(defaultType(round));
      entity.setCategoryKey(plan.categoryKeys().getFirst());
      entity.setQuestion(deterministicQuestion(round, context, i + 1));
      entity.setReferenceAnswer("参考答案将在评估阶段生成。");
      questionRepository.save(entity);
    }
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
    String focus = context != null && !context.skillId().isBlank() ? context.skillId() : round.getRoundType().name();
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
