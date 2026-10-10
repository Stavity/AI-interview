package interview.guide.modules.learning.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Optional AI wording layer. It receives a fixed route and is never allowed to change its order. */
@Service
@Slf4j
@RequiredArgsConstructor
public class LearningPathNarrativeComposer {

  private final LlmProviderRegistry llmProviderRegistry;
  private final LearningProperties properties;

  public Composition compose(String goal, List<LearningRoutePlanner.RouteStep> route,
      Map<String, QuestionKnowledgeNodeEntity> nodesByCode, String ruleSummary) {
    if (!properties.isPathAiEnabled() || route.isEmpty()) {
      return new Composition(ruleSummary, "RULE");
    }
    try {
      String items = route.stream().map(step -> {
        QuestionKnowledgeNodeEntity node = nodesByCode.get(step.nodeCode());
        return (node == null ? step.nodeCode() : node.getName()) + "（" + step.reason() + "）";
      }).reduce((left, right) -> left + "；" + right).orElse("");
      String content = llmProviderRegistry.getPlainChatClient().prompt().user("""
          你是面试学习教练。请仅根据给定的固定学习顺序，写一段不超过180字的中文学习策略。
          不要新增、删除、重排知识点；不要声称看过未提供的资料；要说明先修项优先的原因和下一步行动。
          学习目标：%s
          固定路线：%s
          """.formatted(goal == null || goal.isBlank() ? "提升面试能力" : goal, items)).call().content();
      if (content != null && !content.isBlank() && content.length() <= 1000) {
        return new Composition(content.trim(), "AI");
      }
      log.warn("个性化学习路线 AI 摘要为空或过长，使用规则摘要");
    } catch (Exception e) {
      log.warn("个性化学习路线 AI 编排失败，使用规则摘要: {}", e.getMessage());
    }
    return new Composition(ruleSummary, "RULE");
  }

  public record Composition(String summary, String source) {}
}
