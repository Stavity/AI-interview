package interview.guide.modules.multiround.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Deterministic authority boundary between an ADK proposal and domain mutations. */
@Component
public class WorkflowPolicyGate {
  public static final String VERSION = "workflow-v1";
  private static final Pattern NODE_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");
  private static final Set<String> TYPES = Set.of(
      "ALGORITHM", "TECHNICAL_FOUNDATION", "PROJECT_DEEP_DIVE", "SYSTEM_DESIGN", "HR");
  private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");

  public List<WorkflowNodeDefinition> validateInitial(InitialWorkflowDecision decision) {
    if (decision == null || decision.nodes() == null || decision.nodes().size() < 3 || decision.nodes().size() > 6) {
      throw new IllegalArgumentException("初始工作流必须包含 3 到 6 个节点");
    }
    int totalQuestions = 0;
    Set<String> keys = new HashSet<>();
    boolean hasAlgorithm = false;
    for (int index = 0; index < decision.nodes().size(); index++) {
      WorkflowNodeDefinition node = decision.nodes().get(index);
      if (node == null || node.nodeKey() == null || !NODE_KEY.matcher(node.nodeKey()).matches() || !keys.add(node.nodeKey())) {
        throw new IllegalArgumentException("节点 key 必须唯一且为小写 kebab-case");
      }
      String type = normalized(node.nodeType());
      if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的节点类型: " + node.nodeType());
      if ("HR".equals(type) && index != decision.nodes().size() - 1) {
        throw new IllegalArgumentException("HR 节点只能位于工作流末尾");
      }
      if ("ALGORITHM".equals(type)) hasAlgorithm = true;
      if (node.questionLimit() < 2 || node.questionLimit() > 4) {
        throw new IllegalArgumentException("每个节点题目数必须为 2 到 4");
      }
      if (node.coverage() == null || node.coverage().isEmpty() || node.coverage().size() > 5) {
        throw new IllegalArgumentException("每个节点必须有 1 到 5 个能力覆盖项");
      }
      if (node.difficultyBand() != null && !node.difficultyBand().isBlank()
          && !DIFFICULTIES.contains(normalized(node.difficultyBand()))) {
        throw new IllegalArgumentException("不支持的难度带");
      }
      totalQuestions += node.questionLimit();
    }
    if (!hasAlgorithm) throw new IllegalArgumentException("初始工作流必须包含算法节点");
    if (totalQuestions < 8 || totalQuestions > 16) throw new IllegalArgumentException("总题目数必须为 8 到 16");
    return decision.nodes();
  }

  public List<WorkflowNodePatch> validateMutation(WorkflowMutationDecision decision, long revision,
      Set<String> pendingNodeKeys) {
    if (decision == null || decision.expectedRevision() != revision || decision.patches() == null
        || decision.patches().isEmpty() || decision.patches().size() > pendingNodeKeys.size()) {
      throw new IllegalArgumentException("checkpoint 决策版本或补丁数量非法");
    }
    Set<String> keys = new HashSet<>();
    for (WorkflowNodePatch patch : decision.patches()) {
      if (patch == null || patch.nodeKey() == null || !pendingNodeKeys.contains(patch.nodeKey()) || !keys.add(patch.nodeKey())) {
        throw new IllegalArgumentException("只能修改唯一的未开始节点");
      }
      if (patch.questionLimit() < 2 || patch.questionLimit() > 4 || patch.coverage() == null
          || patch.coverage().isEmpty() || patch.coverage().size() > 5) {
        throw new IllegalArgumentException("未来节点题量或覆盖范围非法");
      }
      if (patch.difficultyBand() != null && !patch.difficultyBand().isBlank()
          && !DIFFICULTIES.contains(normalized(patch.difficultyBand()))) {
        throw new IllegalArgumentException("未来节点难度带非法");
      }
    }
    return decision.patches();
  }

  private String normalized(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
