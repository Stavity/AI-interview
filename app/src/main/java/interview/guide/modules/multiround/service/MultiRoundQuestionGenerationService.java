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
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final int ALGORITHM_QUESTION_COUNT = 3;
  private static final String ALGORITHM_DIFFICULTY = "medium";
  private static final List<String> ALGORITHM_FALLBACKS = List.of(
      "给定一个整数数组 nums 和整数 k，返回和等于 k 的连续子数组个数。\n输入：nums=[1,1,1], k=2\n输出：2\n约束：1 <= nums.length <= 2*10^4，-1000 <= nums[i] <= 1000。\n要求：给出时间复杂度优于 O(n^2) 的解法。",
      "给定一棵二叉树的根节点 root，返回其节点值的垂直遍历结果：同列按行从上到下，同一位置按节点值升序。\n输入：root=[3,9,20,null,null,15,7]\n输出：[[9],[3,15],[20],[7]]\n约束：节点数在 1 到 1000 之间。",
      "给定字符串 s，返回其中最长有效括号子串的长度。\n输入：s=\")()())\"\n输出：4\n约束：0 <= s.length <= 3*10^4，s 仅包含 '(' 和 ')'。\n要求：说明边界条件和空间复杂度。"
  );

  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker outputInvoker;
  private final PromptSanitizer promptSanitizer;
  private final MultiRoundQuestionRepository questionRepository;
  private final ObjectMapper objectMapper;

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
    int limit = questionCount(round);

    try {
      List<GeneratedQuestion> generated = invokeGeneration(interview, round, plan, context, limit);
      for (int i = 0; i < generated.size() && i < limit; i++) {
        GeneratedQuestion q = generated.get(i);
        MultiRoundQuestionEntity entity = new MultiRoundQuestionEntity();
        entity.setRound(round);
        entity.setSequenceNo(i + 1);
        entity.setQuestionType(questionType(round, q.questionType()));
        entity.setDifficulty(questionDifficulty(round));
        entity.setCategoryKey(plan.categoryKeys().contains(q.categoryKey())
            ? q.categoryKey() : plan.categoryKeys().getFirst());
        entity.setQuestion(questionText(round, q));
        entity.setProblemJson(problemJson(round, q));
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
        """.formatted(count, round.getRoundType().name(), String.join(",", plan.categoryKeys()));
    if (round.getRoundType() == MultiRoundEntity.Type.ALGORITHM) {
      systemPrompt += """
          这是算法轮：必须生成三道互不重复的 LeetCode Medium 风格算法题。
          每道题的 questionType 必须是 ALGORITHM。必须额外返回 title、description、inputFormat、outputFormat、constraints、examples 字段。
          constraints 是字符串数组；examples 是对象数组，每个对象必须有 input、output、explanation。
          所有字段均使用清晰的中文；不得把输入、输出、范围和样例混写在 question 字段中；不得生成八股、系统设计或行为题。
          """;
    }
    systemPrompt += converter.getFormat();

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
      entity.setQuestionType(questionType(round, null));
      entity.setDifficulty(questionDifficulty(round));
      entity.setCategoryKey(plan.categoryKeys().getFirst());
      entity.setQuestion(deterministicQuestion(round, context, i + 1));
      entity.setProblemJson(deterministicProblemJson(round, i + 1));
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

  private int questionCount(MultiRoundEntity round) {
    return round.getRoundType() == MultiRoundEntity.Type.ALGORITHM
        ? ALGORITHM_QUESTION_COUNT : round.getQuestionLimit();
  }

  private String questionType(MultiRoundEntity round, String generatedType) {
    return round.getRoundType() == MultiRoundEntity.Type.ALGORITHM
        ? "ALGORITHM" : generatedType != null ? generatedType : defaultType(round);
  }

  private String questionDifficulty(MultiRoundEntity round) {
    return round.getRoundType() == MultiRoundEntity.Type.ALGORITHM ? ALGORITHM_DIFFICULTY : null;
  }

  private String deterministicQuestion(MultiRoundEntity round, InterviewPlanningContext context, int index) {
    String focus = context != null && !context.skillId().isBlank() ? context.skillId() : round.getRoundType().name();
    return switch (round.getRoundType()) {
      case ALGORITHM -> ALGORITHM_FALLBACKS.get(index - 1);
      case TECHNICAL_1 -> "请结合简历中的 %s 经历，解释相关技术原理与实际取舍。".formatted(focus);
      case TECHNICAL_2 -> "请围绕 %s 设计一个具备扩展性和高可用能力的系统方案。".formatted(focus);
      case HR -> "请结合简历中与 %s 相关的经历，描述一次挑战及解决方式。".formatted(focus);
    };
  }

  private String questionText(MultiRoundEntity round, GeneratedQuestion question) {
    if (round.getRoundType() != MultiRoundEntity.Type.ALGORITHM || blank(question.description())) return question.question();
    return question.title() + "\n\n" + question.description() + "\n\n输入：" + question.inputFormat()
        + "\n输出：" + question.outputFormat() + "\n约束：" + String.join("；", safe(question.constraints()));
  }

  private String problemJson(MultiRoundEntity round, GeneratedQuestion question) {
    if (round.getRoundType() != MultiRoundEntity.Type.ALGORITHM || blank(question.description())) return null;
    return writeProblem(new AlgorithmProblem(question.title(), question.description(), question.inputFormat(),
        question.outputFormat(), safe(question.constraints()), safe(question.examples())));
  }

  private String deterministicProblemJson(MultiRoundEntity round, int index) {
    if (round.getRoundType() != MultiRoundEntity.Type.ALGORITHM) return null;
    return switch (index) {
      case 1 -> writeProblem(new AlgorithmProblem("和为 K 的子数组", "统计整数数组中和恰好等于 k 的连续非空子数组数量。",
          "nums: 整数数组；k: 整数", "一个整数，满足条件的连续子数组数量", List.of("1 <= nums.length <= 2 * 10^4", "-1000 <= nums[i] <= 1000"),
          List.of(new AlgorithmExample("nums = [1,1,1], k = 2", "2", "两个 [1,1] 子数组的和为 2"))));
      case 2 -> writeProblem(new AlgorithmProblem("二叉树的垂序遍历", "返回二叉树节点的垂序遍历结果；同列按行优先，同一坐标按节点值升序。",
          "root: 二叉树根节点，使用层序数组表示", "二维整数数组，每个内层数组表示一列", List.of("节点数范围为 1 到 1000"),
          List.of(new AlgorithmExample("root = [3,9,20,null,null,15,7]", "[[9],[3,15],[20,7]]", "按列从左到右收集节点"))));
      default -> writeProblem(new AlgorithmProblem("最长有效括号", "给定仅由左右括号组成的字符串，返回最长连续有效括号子串的长度。",
          "s: 仅包含 '(' 与 ')' 的字符串", "一个整数，最长有效括号子串长度", List.of("0 <= s.length <= 3 * 10^4"),
          List.of(new AlgorithmExample("s = \")()())\"", "4", "最长有效子串为 ()()"))));
    };
  }

  private String writeProblem(AlgorithmProblem problem) {
    try { return objectMapper.writeValueAsString(problem); }
    catch (Exception error) { throw new IllegalStateException("算法题面序列化失败", error); }
  }
  private boolean blank(String value) { return value == null || value.isBlank(); }
  private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

  public record GeneratedQuestion(String questionType, String categoryKey, String question, String referenceAnswer,
                                  String title, String description, String inputFormat, String outputFormat,
                                  List<String> constraints, List<AlgorithmExample> examples) {}
  public record AlgorithmProblem(String title, String description, String inputFormat, String outputFormat,
                                 List<String> constraints, List<AlgorithmExample> examples) {}
  public record AlgorithmExample(String input, String output, String explanation) {}
  public record GeneratedQuestionList(List<GeneratedQuestion> questions) {}
}
