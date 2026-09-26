package interview.guide.modules.questiontag.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeAliasEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity.Status;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeAliasRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Deterministic, explainable matcher for historical question metadata; it never calls an LLM. */
@Component
@RequiredArgsConstructor
public class QuestionTagBackfillMatcher {

  private static final double MIN_REVIEW_CONFIDENCE = 0.70;
  private static final double AUTO_VERIFY_CONFIDENCE = 0.88;
  private static final double MIN_AUTO_VERIFY_MARGIN = 0.12;

  private final QuestionTagQueryService queryService;
  private final QuestionKnowledgeNodeRepository nodeRepository;
  private final QuestionKnowledgeNodeAliasRepository aliasRepository;
  private final Map<Integer, List<CatalogNode>> catalogCache = new ConcurrentHashMap<>();
  private final Map<ScopeKey, Set<String>> rootCache = new ConcurrentHashMap<>();

  public MatchResult match(
      int taxonomyVersion,
      String skillId,
      String type,
      String category,
      String topicSummary,
      String question
  ) {
    Set<String> roots = rootCache.computeIfAbsent(new ScopeKey(taxonomyVersion, skillId), key -> {
      try {
        return Set.copyOf(queryService.generationRoots(key.version(), key.skillId()));
      } catch (BusinessException e) {
        // Unknown skills must not silently broaden historical matching to the whole taxonomy.
        return Set.of();
      }
    });
    List<ScoredNode> scores = catalogCache.computeIfAbsent(taxonomyVersion, this::loadCatalog).stream()
        .filter(CatalogNode::assignable)
        .filter(node -> isWithinScope(node.code(), roots))
        .map(node -> new ScoredNode(node, score(node, type, category, topicSummary, question)))
        .filter(item -> item.score() > 0)
        .sorted(Comparator.comparingDouble(ScoredNode::score).reversed()
            .thenComparing(item -> item.node().code()))
        .toList();
    if (scores.isEmpty() || scores.get(0).score() < MIN_REVIEW_CONFIDENCE) {
      return MatchResult.none();
    }
    ScoredNode best = scores.get(0);
    double second = scores.size() > 1 ? scores.get(1).score() : 0;
    boolean verified = best.score() >= AUTO_VERIFY_CONFIDENCE
        && best.score() - second >= MIN_AUTO_VERIFY_MARGIN;
    BigDecimal confidence = BigDecimal.valueOf(best.score()).setScale(4, java.math.RoundingMode.HALF_UP);
    return new MatchResult(best.node().code(), confidence, verified);
  }

  private List<CatalogNode> loadCatalog(int version) {
    Map<String, List<String>> aliases = aliasRepository.findByTaxonomyVersion(version).stream()
        .collect(Collectors.groupingBy(QuestionKnowledgeNodeAliasEntity::getNodeCode,
            Collectors.mapping(QuestionKnowledgeNodeAliasEntity::getAlias, Collectors.toList())));
    return nodeRepository.findByTaxonomyVersionAndStatusOrderBySortOrderAscCodeAsc(version, Status.ACTIVE)
        .stream().map(node -> new CatalogNode(node.getCode(), node.getName(), node.isAssignable(),
            aliases.getOrDefault(node.getCode(), List.of())))
        .toList();
  }

  private double score(
      CatalogNode node, String type, String category, String topicSummary, String question
  ) {
    double score = 0;
    // Legacy type/category values are coarse labels; type alone may suggest a candidate,
    // but must not be enough to auto-verify a historical tag.
    score = Math.max(score, fieldScore(type, node, 0.82, 0.74));
    score = Math.max(score, fieldScore(category, node, 0.96, 0.90));
    score = Math.max(score, fieldScore(topicSummary, node, 0.93, 0.85));
    score = Math.max(score, fieldScore(question, node, 0.78, 0.71));
    return score;
  }

  private double fieldScore(String raw, CatalogNode node, double exact, double contains) {
    String source = normalize(raw);
    if (source.isEmpty()) {
      return 0;
    }
    List<String> labels = new ArrayList<>();
    labels.add(node.name());
    labels.addAll(node.aliases());
    String leafCode = node.code().substring(node.code().lastIndexOf('.') + 1);
    labels.add(leafCode);
    double best = 0;
    for (String label : labels) {
      String normalizedLabel = normalize(label);
      if (normalizedLabel.length() < 2) {
        continue;
      }
      if (source.equals(normalizedLabel)) {
        best = Math.max(best, exact);
      } else if (source.contains(normalizedLabel)) {
        best = Math.max(best, contains);
      } else if (normalizedLabel.length() >= 4 && normalizedLabel.contains(source)) {
        best = Math.max(best, contains - 0.06);
      }
    }
    return best;
  }

  private boolean isWithinScope(String code, Set<String> roots) {
    return roots.stream().anyMatch(root -> "TECH_KNOWLEDGE".equals(root)
        || code.equals(root) || code.startsWith(root + "."));
  }

  private String normalize(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s\\p{Punct}，。；：！？（）【】《》、\\-_/]+", "");
  }

  public record MatchResult(String primaryCode, BigDecimal confidence, boolean verified) {
    public static MatchResult none() {
      return new MatchResult(null, null, false);
    }

    public boolean matched() {
      return primaryCode != null;
    }
  }

  private record ScopeKey(int version, String skillId) {}
  private record CatalogNode(String code, String name, boolean assignable, List<String> aliases) {}
  private record ScoredNode(CatalogNode node, double score) {}
}
