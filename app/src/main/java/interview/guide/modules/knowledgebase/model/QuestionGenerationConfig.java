package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库题目生成参数快照。
 */
public record QuestionGenerationConfig(
    String difficulty,
    int questionCount,
    int followUpCount,
    int categoryLimit,
    String llmProvider,
    Integer taxonomyVersion,
    List<String> allowedRootCodes,
    String taggingPolicyVersion
) {
  public QuestionGenerationConfig(String difficulty, int questionCount, int followUpCount,
                                  int categoryLimit, String llmProvider) {
    this(difficulty, questionCount, followUpCount, categoryLimit, llmProvider,
        null, List.of(), null);
  }

  public QuestionGenerationConfig {
    allowedRootCodes = allowedRootCodes == null ? List.of() : List.copyOf(allowedRootCodes);
  }
}
