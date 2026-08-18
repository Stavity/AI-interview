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

  public Review review(String providerId, String language, String sourceCode) {
    if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > MAX_CODE_LENGTH) {
      throw new IllegalArgumentException("代码不能为空且不能超过 50000 字符");
    }
    var converter = new BeanOutputConverter<>(Review.class);
    String system = "你是算法代码静态评审器。严禁执行、编译、加载代码或调用工具。仅根据文本代码分析思路、复杂度、边界、质量和改进建议，score 必须为 0 到 100 的整数。\n" + converter.getFormat();
    String user = "语言：" + language + "\n代码：\n" + sourceCode;
    return outputInvoker.invoke(registry.getPlainChatClient(providerId), system, user, converter,
        ErrorCode.AI_SERVICE_ERROR, "算法静态评审失败: ", "[algorithm-static-review] ", LoggerFactory.getLogger(AlgorithmStaticReviewService.class));
  }

  public record Review(int score, String approach, String timeComplexity, String spaceComplexity,
                       List<String> boundaryCases, List<String> qualityIssues, List<String> improvements) {}
}
