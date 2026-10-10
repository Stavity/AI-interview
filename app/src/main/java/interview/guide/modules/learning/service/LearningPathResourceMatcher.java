package interview.guide.modules.learning.service;

import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Deterministically matches a taxonomy node to the maintained skill catalogue.  It deliberately
 * returns no resource instead of suggesting a broad but unrelated category.
 */
@Service
public class LearningPathResourceMatcher {

  private static final Map<String, List<String>> CATEGORY_CODE_HINTS = Map.ofEntries(
      Map.entry("JAVA", List.of(".JAVA.")),
      Map.entry("MYSQL", List.of("MYSQL", "DATABASE.MYSQL")),
      Map.entry("REDIS", List.of("REDIS")),
      Map.entry("SPRING", List.of("SPRING")),
      Map.entry("CACHE", List.of("CACHE")),
      Map.entry("MQ", List.of("MQ", "MESSAGE_QUEUE")),
      Map.entry("DISTRIBUTED", List.of("DISTRIBUTED", "DISTRIBUTION")),
      Map.entry("HIGH_AVAILABILITY", List.of("HIGH_AVAILABILITY")),
      Map.entry("PYTHON_BASIC", List.of(".PYTHON.")),
      Map.entry("JAVASCRIPT", List.of("JAVASCRIPT", "TYPESCRIPT")),
      Map.entry("REACT_VUE", List.of("REACT", "VUE")),
      Map.entry("ARRAY_LINKEDLIST", List.of("ARRAY", "LINKED_LIST")),
      Map.entry("HASH_HEAP_QUEUE", List.of("HASH", "HEAP", "STACK_QUEUE")),
      Map.entry("TREE_GRAPH", List.of("TREE", "GRAPH")),
      Map.entry("DP", List.of("DYNAMIC_PROGRAMMING")),
      Map.entry("GREEDY_BACKTRACK", List.of("GREEDY", "BACKTRACK")),
      Map.entry("RAG", List.of(".RAG.")),
      Map.entry("LLM_CALLING", List.of(".LLM.")),
      Map.entry("MCP_PROTOCOL", List.of(".MCP.")));

  public List<Match> match(QuestionKnowledgeNodeEntity node, List<LearningSkillSourceEntity> sources) {
    String code = node.getCode().toUpperCase(Locale.ROOT);
    String nodeText = (node.getName() + " " + nullToEmpty(node.getDescription())).toLowerCase(Locale.ROOT);
    return sources.stream()
        .map(source -> score(source, code, nodeText))
        .filter(match -> match.score() > 0)
        .sorted(Comparator.comparingInt(Match::score).reversed()
            .thenComparing(match -> match.source().getId()))
        .limit(2)
        .toList();
  }

  private Match score(LearningSkillSourceEntity source, String nodeCode, String nodeText) {
    List<String> hints = CATEGORY_CODE_HINTS.get(source.getCategoryKey().toUpperCase(Locale.ROOT));
    if (hints != null && hints.stream().anyMatch(nodeCode::contains)) {
      return new Match(source, 100, "资源分类与知识点目录代码直接匹配。" );
    }
    String label = source.getDisplayName().trim().toLowerCase(Locale.ROOT);
    if (label.length() >= 2 && nodeText.contains(label)) {
      return new Match(source, 80, "资源标题出现在知识点名称或说明中。");
    }
    return new Match(source, 0, "");
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public record Match(LearningSkillSourceEntity source, int score, String reason) {}
}
