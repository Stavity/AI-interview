package interview.guide.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.learning.service.LearningEvidenceAggregator.KnowledgeNode;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.NodeProgress;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.ProgressStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LearningRoutePlannerTest {

  private final LearningRoutePlanner planner = new LearningRoutePlanner();

  @Test
  @DisplayName("只把已评估的薄弱点作为目标，并把未掌握前置点排在目标之前")
  void shouldPlaceUnmasteredPrerequisiteBeforeWeakTarget() {
    List<KnowledgeNode> nodes = List.of(
        node("JAVA.JVM.MEMORY", true, 0),
        node("JAVA.JVM.GC", true, 1),
        node("JAVA.JVM.CLASSLOADING", true, 2));
    List<NodeProgress> progress = List.of(
        progress("JAVA.JVM.GC", 40, 2, ProgressStatus.LEARNING),
        progress("JAVA.JVM.CLASSLOADING", 100, 3, ProgressStatus.MASTERED));

    List<LearningRoutePlanner.RouteStep> route = planner.plan(nodes, progress, List.of(
        new LearningRoutePlanner.Prerequisite("JAVA.JVM.MEMORY", "JAVA.JVM.GC")), 12);

    assertThat(route).extracting(LearningRoutePlanner.RouteStep::nodeCode)
        .containsExactly("JAVA.JVM.MEMORY", "JAVA.JVM.GC");
    assertThat(route.getFirst().prerequisite()).isTrue();
    assertThat(route.getFirst().masteryScore()).isZero();
    assertThat(route.getLast().prerequisite()).isFalse();
  }

  @Test
  @DisplayName("没有证据时不把零掌握度解释成薄弱知识点，也不虚构先修关系")
  void shouldReturnNoRouteWithoutEvidenceOrPrerequisite() {
    List<LearningRoutePlanner.RouteStep> route = planner.plan(
        List.of(node("JAVA.JVM.GC", true, 0)), List.of(), List.of(), 12);

    assertThat(route).isEmpty();
  }

  @Test
  @DisplayName("目录先修关系成环时拒绝生成错误顺序")
  void shouldRejectCyclicPrerequisiteGraph() {
    List<KnowledgeNode> nodes = List.of(node("A", true, 0), node("B", true, 1));
    List<NodeProgress> progress = List.of(
        progress("A", 40, 2, ProgressStatus.LEARNING),
        progress("B", 50, 2, ProgressStatus.LEARNING));

    assertThatThrownBy(() -> planner.plan(nodes, progress, List.of(
        new LearningRoutePlanner.Prerequisite("A", "B"),
        new LearningRoutePlanner.Prerequisite("B", "A")), 12))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("包含循环");
  }

  private static KnowledgeNode node(String code, boolean assignable, int order) {
    return new KnowledgeNode(code, null, assignable, true, order);
  }

  private static NodeProgress progress(String code, int score, int sessions, ProgressStatus status) {
    return new NodeProgress(code, null, score, sessions, sessions, 0, 0, 100, status,
        LocalDateTime.of(2026, 9, 27, 12, 0));
  }
}
