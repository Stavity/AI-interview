package interview.guide.common.ai.agent;

/**
 * Immutable budget configuration for a single Agent orchestration run.
 * Enforces max planning steps, max follow-ups per question, input/output
 * character limits, timeout, and retry count as specified by the TRD.
 */
public record AgentBudget(
    int maxPlanningSteps,
    int maxFollowUpsPerQuestion,
    int maxInputCharacters,
    int maxOutputCharacters,
    long timeoutMs,
    int maxRetries
) {
  public static final AgentBudget DEFAULT = new AgentBudget(
      6,   // max planning steps per TRD
      2,   // max follow-ups per question per TRD
      12_000,
      8_000,
      60_000,
      1
  );

  public void validateInputLength(String input) {
    if (input != null && input.length() > maxInputCharacters) {
      throw new IllegalArgumentException("Agent 输入超过 " + maxInputCharacters + " 字符上限");
    }
  }

  public void validateOutputLength(String output) {
    if (output != null && output.length() > maxOutputCharacters) {
      throw new IllegalArgumentException("Agent 输出超过 " + maxOutputCharacters + " 字符上限");
    }
  }

  public void validateStepCount(int stepCount) {
    if (stepCount < 1 || stepCount > maxPlanningSteps) {
      throw new IllegalArgumentException(
          "Agent 规划步骤 " + stepCount + " 超出预算 [1, " + maxPlanningSteps + "]");
    }
  }

  public void validateFollowUpCount(int followUpCount) {
    if (followUpCount < 0 || followUpCount > maxFollowUpsPerQuestion) {
      throw new IllegalArgumentException(
          "Agent 追问次数 " + followUpCount + " 超出预算 [0, " + maxFollowUpsPerQuestion + "]");
    }
  }
}
