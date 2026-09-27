package interview.guide.modules.learning.service;

import static interview.guide.modules.learning.service.LearningEvidenceAggregator.ProgressStatus.MASTERED;

import interview.guide.modules.learning.service.LearningEvidenceAggregator.KnowledgeNode;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.NodeProgress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Builds a deterministic knowledge-node route from one user's progress snapshot. */
public class LearningRoutePlanner {

  private static final int DEFAULT_LIMIT = 12;

  public List<RouteStep> plan(
      Collection<KnowledgeNode> taxonomy,
      Collection<NodeProgress> progress,
      Collection<Prerequisite> prerequisites,
      int requestedLimit
  ) {
    if (requestedLimit < 1) {
      throw new IllegalArgumentException("requestedLimit must be positive");
    }
    int limit = Math.min(requestedLimit, DEFAULT_LIMIT);
    Map<String, KnowledgeNode> nodes = new HashMap<>();
    taxonomy.stream().filter(KnowledgeNode::active).forEach(node -> nodes.put(node.code(), node));
    Map<String, NodeProgress> progressByCode = new HashMap<>();
    progress.forEach(item -> progressByCode.put(item.nodeCode(), item));

    Set<String> weakTargets = new LinkedHashSet<>();
    for (KnowledgeNode node : nodes.values()) {
      NodeProgress value = progressByCode.get(node.code());
      if (node.assignable() && value != null && value.distinctSessionCount() > 0
          && value.status() != MASTERED) {
        weakTargets.add(node.code());
      }
    }
    if (weakTargets.isEmpty()) {
      return List.of();
    }

    Map<String, List<String>> prereqsByTarget = new HashMap<>();
    for (Prerequisite relation : prerequisites) {
      if (nodes.containsKey(relation.prerequisiteCode()) && nodes.containsKey(relation.targetCode())) {
        prereqsByTarget.computeIfAbsent(relation.targetCode(), ignored -> new ArrayList<>())
            .add(relation.prerequisiteCode());
      }
    }

    Set<String> candidates = new LinkedHashSet<>();
    for (String target : weakTargets) {
      collectUnmasteredPrerequisites(target, prereqsByTarget, progressByCode, candidates, new HashSet<>());
      candidates.add(target);
    }

    Map<String, Set<String>> outgoing = new HashMap<>();
    Map<String, Integer> inDegree = new HashMap<>();
    candidates.forEach(code -> inDegree.put(code, 0));
    for (String target : candidates) {
      for (String prerequisite : prereqsByTarget.getOrDefault(target, List.of())) {
        if (candidates.contains(prerequisite)
            && outgoing.computeIfAbsent(prerequisite, ignored -> new HashSet<>()).add(target)) {
          inDegree.compute(target, (ignored, value) -> value + 1);
        }
      }
    }

    Comparator<String> priority = Comparator
        .comparingInt((String code) -> score(progressByCode.get(code)))
        .thenComparing(code -> lastPracticed(progressByCode.get(code)),
            Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(code -> nodes.get(code).sortOrder())
        .thenComparing(code -> code);
    PriorityQueue<String> ready = new PriorityQueue<>(priority);
    inDegree.forEach((code, degree) -> {
      if (degree == 0) {
        ready.add(code);
      }
    });

    List<String> ordered = new ArrayList<>();
    while (!ready.isEmpty()) {
      String code = ready.remove();
      ordered.add(code);
      for (String next : outgoing.getOrDefault(code, Set.of())) {
        int remaining = inDegree.compute(next, (ignored, value) -> value - 1);
        if (remaining == 0) {
          ready.add(next);
        }
      }
    }
    if (ordered.size() < candidates.size()) {
      throw new IllegalStateException("题目知识目录的先修关系包含循环，无法生成有序学习路线");
    }

    List<RouteStep> route = new ArrayList<>();
    for (String code : ordered) {
      if (route.size() == limit) {
        break;
      }
      KnowledgeNode node = nodes.get(code);
      NodeProgress value = progressByCode.get(code);
      boolean prerequisite = !weakTargets.contains(code);
      String reason = prerequisite
          ? "该知识点是后续薄弱知识点的前置内容，建议先补齐。"
          : "近期面试评估掌握度为 " + score(value) + "，建议优先复习。";
      route.add(new RouteStep(code, node.parentCode(), score(value),
          value == null ? 0 : value.distinctSessionCount(), prerequisite, reason));
    }
    return List.copyOf(route);
  }

  private void collectUnmasteredPrerequisites(
      String target,
      Map<String, List<String>> prereqsByTarget,
      Map<String, NodeProgress> progressByCode,
      Set<String> candidates,
      Set<String> visiting
  ) {
    if (!visiting.add(target)) {
      return;
    }
    for (String prerequisite : prereqsByTarget.getOrDefault(target, List.of())) {
      NodeProgress value = progressByCode.get(prerequisite);
      if (value == null || value.status() != MASTERED) {
        candidates.add(prerequisite);
        collectUnmasteredPrerequisites(prerequisite, prereqsByTarget, progressByCode, candidates, visiting);
      }
    }
    visiting.remove(target);
  }

  private static int score(NodeProgress value) {
    return value == null ? 0 : value.masteryScore();
  }

  private static LocalDateTime lastPracticed(NodeProgress value) {
    return value == null ? null : value.lastPracticedAt();
  }

  public record Prerequisite(String prerequisiteCode, String targetCode) {}

  public record RouteStep(
      String nodeCode,
      String parentCode,
      int masteryScore,
      int distinctSessionCount,
      boolean prerequisite,
      String reason
  ) {}
}
