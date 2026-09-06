package interview.guide.modules.multiround.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlgorithmStaticReviewService {
  private static final int MAX_CODE_LENGTH = 50_000;
  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker outputInvoker;

  public Review review(String providerId, String language, String question, String sourceCode) {
    if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > MAX_CODE_LENGTH) {
      throw new IllegalArgumentException("代码不能为空且不能超过 50000 字符");
    }
    var converter = new BeanOutputConverter<>(Review.class);
    String system = """
        你是算法代码静态评审器。严禁执行、编译、加载代码或调用工具，只能根据题面和代码作静态推理。
        首先以题目要求为准判断 correctnessPassed（算法是否能覆盖题意、样例、边界）和 complexityPassed（时间、空间复杂度是否满足题面约束所要求的复杂度）。
        必须给出 requiredTimeComplexity、requiredSpaceComplexity 和 blockingIssues。代码风格、命名、微小常数和性能偏好不得降低一份正确且满足复杂度要求的解答。
        硬性评分规则：correctnessPassed 和 complexityPassed 都为 true 时，score 必须为 100；任一为 false 时，score 必须低于 100，并在 blockingIssues 说明阻塞原因。
        score 必须为 0 到 100 的整数。
        """ + converter.getFormat();
    String user = "题目：\n" + question + "\n\n语言：" + language + "\n\n代码：\n" + sourceCode;
    Review review = outputInvoker.invoke(registry.getPlainChatClient(providerId), system, user, converter,
        ErrorCode.AI_SERVICE_ERROR, "算法静态评审失败: ", "[algorithm-static-review] ", LoggerFactory.getLogger(AlgorithmStaticReviewService.class));
    return normalize(review);
  }

  static Review normalize(Review review) {
    int score = review.correctnessPassed() && review.complexityPassed() ? 100 : Math.max(0, Math.min(99, review.score()));
    return new Review(score, review.correctnessPassed(), review.complexityPassed(), review.requiredTimeComplexity(),
        review.requiredSpaceComplexity(), review.approach(), review.timeComplexity(), review.spaceComplexity(),
        review.boundaryCases(), review.blockingIssues(), review.qualityIssues(), review.improvements());
  }

  public record Review(int score, boolean correctnessPassed, boolean complexityPassed,
                       String requiredTimeComplexity, String requiredSpaceComplexity,
                       String approach, String timeComplexity, String spaceComplexity,
                       List<String> boundaryCases, List<String> blockingIssues,
                       List<String> qualityIssues, List<String> improvements) {}
}
