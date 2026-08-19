package interview.guide.common.ai.adk;

import interview.guide.common.ai.agent.AgentRunMetrics;
import java.util.Set;

/** Explicit allow-list and per-run budget for every ADK function call. */
public final class AdkToolPolicy {
  private static final Set<String> READ_ONLY_TOOLS = Set.of(
      "read_interview_snapshot", "read_prior_answers", "read_round_reports", "read_question_coverage");

  private final int maxToolCalls;
  private final AgentRunMetrics metrics;
  private int toolCalls;

  public AdkToolPolicy(int maxToolCalls) {
    this(maxToolCalls, null);
  }

  public AdkToolPolicy(int maxToolCalls, AgentRunMetrics metrics) {
    if (maxToolCalls < 1) throw new IllegalArgumentException("Tool 调用上限必须大于 0");
    this.maxToolCalls = maxToolCalls;
    this.metrics = metrics;
  }

  public void authorize(String toolName) {
    if (!READ_ONLY_TOOLS.contains(toolName)) {
      record(toolName, "REJECTED");
      throw new IllegalArgumentException("Tool 不在白名单: " + toolName);
    }
    if (++toolCalls > maxToolCalls) {
      record(toolName, "REJECTED");
      throw new IllegalStateException("Tool 调用次数超过预算: " + maxToolCalls);
    }
    record(toolName, "SUCCEEDED");
  }

  public int remainingCalls() {
    return maxToolCalls - toolCalls;
  }

  private void record(String toolName, String status) {
    if (metrics != null) metrics.recordToolCall(toolName, status);
  }
}
