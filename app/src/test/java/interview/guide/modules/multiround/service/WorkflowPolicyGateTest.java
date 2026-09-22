package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowPolicyGateTest {
  private final WorkflowPolicyGate gate = new WorkflowPolicyGate();

  @Test
  @DisplayName("接受满足节点数量、题量、类型和 HR 末位约束的 Root 决策")
  void acceptsBoundedInitialGraph() {
    List<WorkflowNodeDefinition> accepted = gate.validateInitial(new InitialWorkflowDecision("覆盖候选人项目与基础能力", List.of(
        node("algorithm-core", "ALGORITHM", 3),
        node("foundation-java", "TECHNICAL_FOUNDATION", 3),
        node("project-deep-dive", "PROJECT_DEEP_DIVE", 3),
        node("hr-fit", "HR", 2))));

    assertThat(accepted).hasSize(4);
  }

  @Test
  @DisplayName("拒绝将 HR 放在尚未完成的技术节点之前")
  void rejectsHrBeforeLastNode() {
    InitialWorkflowDecision decision = new InitialWorkflowDecision("bad", List.of(
        node("algorithm-core", "ALGORITHM", 3),
        node("hr-fit", "HR", 2),
        node("project-deep-dive", "PROJECT_DEEP_DIVE", 3)));

    assertThatThrownBy(() -> gate.validateInitial(decision))
        .hasMessageContaining("HR 节点只能位于工作流末尾");
  }

  @Test
  @DisplayName("checkpoint 补丁不能修改已开始节点，且必须匹配 workflow revision")
  void rejectsCheckpointPatchForStartedNodeOrStaleRevision() {
    WorkflowMutationDecision decision = new WorkflowMutationDecision(7, "adjust", List.of(
        new WorkflowNodePatch("started-node", 3, "HARD", List.of("system-design"))));

    assertThatThrownBy(() -> gate.validateMutation(decision, 8, java.util.Set.of("future-node")))
        .hasMessageContaining("checkpoint 决策版本或补丁数量非法");
    assertThatThrownBy(() -> gate.validateMutation(
        new WorkflowMutationDecision(8, "adjust", List.of(
            new WorkflowNodePatch("started-node", 3, "HARD", List.of("system-design")))), 8,
        java.util.Set.of("future-node")))
        .hasMessageContaining("只能修改唯一的未开始节点");
  }

  private WorkflowNodeDefinition node(String key, String type, int questions) {
    return new WorkflowNodeDefinition(key, type, questions, "MEDIUM", List.of("能力覆盖"));
  }
}
