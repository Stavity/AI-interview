package interview.guide.modules.questiontag.service;

import interview.guide.modules.questiontag.model.QuestionSourceType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuestionGenerationScopeFactory {

  public static final String TAGGING_POLICY_VERSION = "question-tagging-v1";

  private final QuestionTagQueryService queryService;
  private final int maxToolCalls;

  public QuestionGenerationScopeFactory(
      QuestionTagQueryService queryService,
      @Value("${app.question-tags.generation.max-tool-calls:8}") int maxToolCalls
  ) {
    this.queryService = queryService;
    this.maxToolCalls = Math.max(1, Math.min(maxToolCalls, 20));
  }

  public QuestionGenerationScope current(String skillId, QuestionSourceType sourceType) {
    int version = queryService.currentVersionNumber();
    List<String> roots = queryService.generationRoots(version, skillId);
    return new QuestionGenerationScope(skillId, sourceType, version,
        new LinkedHashSet<>(roots), maxToolCalls);
  }

  public QuestionGenerationScope snapshot(
      String skillId,
      QuestionSourceType sourceType,
      Integer version,
      List<String> roots
  ) {
    if (version == null || roots == null || roots.isEmpty()) {
      return current(skillId, sourceType);
    }
    return new QuestionGenerationScope(skillId, sourceType, version,
        new LinkedHashSet<>(roots), maxToolCalls);
  }

  public QuestionGenerationScope snapshotOrCurrent(
      String skillId,
      QuestionSourceType sourceType,
      Integer version,
      List<String> roots
  ) {
    return version == null || roots == null || roots.isEmpty()
        ? current(skillId, sourceType)
        : snapshot(skillId, sourceType, version, roots);
  }

  /** Compact server-derived candidates for providers that cannot complete tool calls. */
  public String fallbackCandidateSection(QuestionGenerationScope scope, List<String> queryTerms) {
    List<String> queries = queryTerms == null ? List.of() : queryTerms.stream()
        .filter(term -> term != null && !term.isBlank())
        .map(String::trim)
        .map(term -> term.length() > 100 ? term.substring(0, 100) : term)
        .distinct().limit(10).toList();
    if (queries.isEmpty()) {
      return "（当前范围内没有可用候选标签）";
    }
    List<QuestionTagQueryService.GenerationMatch> candidates = queryService.queryForGeneration(
            scope.taxonomyVersion(), scope.allowedRootCodes(), null, queries, true, 20)
        .results().stream().flatMap(result -> result.nodes().stream())
        .collect(Collectors.collectingAndThen(
            Collectors.toMap(QuestionTagQueryService.GenerationMatch::code, match -> match,
                (first, ignored) -> first, java.util.LinkedHashMap::new),
            map -> map.values().stream().limit(30).toList()));
    if (candidates.isEmpty()) {
      return "（根据当前主题未检索到候选标签；请勿生成题目）";
    }
    String result = candidates.stream().map(candidate -> "- " + candidate.code() + " | "
        + candidate.name() + " | " + candidate.path() + " | "
        + (candidate.description() == null ? "" : candidate.description()))
        .collect(Collectors.joining("\n"));
    return result.length() <= 9_000 ? result : result.substring(0, 9_000);
  }
}
