package interview.guide.common.ai.adk;

import com.google.adk.tools.Annotations.Schema;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import java.util.List;
import java.util.StringJoiner;

/** ADK function tools bound to one immutable planning context. */
public final class AdkInterviewReadTools {
  private static final int MAX_ITEMS = 20;
  private final InterviewPlanningContext context;
  private final AdkToolPolicy policy;
  private final AdkToolAuditSink auditSink;

  public AdkInterviewReadTools(InterviewPlanningContext context, AdkToolPolicy policy) {
    this(context, policy, null);
  }

  AdkInterviewReadTools(InterviewPlanningContext context, AdkToolPolicy policy, AdkToolAuditSink auditSink) {
    this.context = context;
    this.policy = policy;
    this.auditSink = auditSink;
  }

  @Schema(name = "read_interview_snapshot", description = "Read the current interview's sanitized snapshot.")
  public String readInterviewSnapshot() {
    return execute("read_interview_snapshot", () -> "skill=" + context.skillId() + ", difficulty=" + context.difficulty()
        + ", round=" + context.roundNo() + ":" + context.roundType()
        + ", resume=" + limit(context.resumeSnapshot()) + ", jd=" + limit(context.jdSnapshot()));
  }

  @Schema(name = "read_prior_answers", description = "Read bounded answer summaries from earlier rounds only.")
  public String readPriorAnswers(@Schema(name = "maxItems", description = "Number of answer summaries, 1 to 20.") int maxItems) {
    return execute("read_prior_answers", () -> join("answers", context.previousAnswers(), maxItems));
  }

  @Schema(name = "read_round_reports", description = "Read bounded reports from completed earlier rounds only.")
  public String readRoundReports(@Schema(name = "maxItems", description = "Number of report summaries, 1 to 20.") int maxItems) {
    return execute("read_round_reports", () -> join("reports", context.priorRoundReportSummaries(), maxItems));
  }

  @Schema(name = "read_question_coverage", description = "Read current round coverage constraints without database access.")
  public String readQuestionCoverage() {
    return execute("read_question_coverage", () -> "roundType=" + context.roundType() + ", questionLimit=" + context.questionLimit()
        + ", followUpLimit=" + context.followUpLimit() + ", priorQuestions="
        + context.priorQuestionSummaries().size());
  }

  private String join(String label, List<String> values, int maxItems) {
    if (maxItems < 1 || maxItems > MAX_ITEMS) {
      throw new IllegalArgumentException("maxItems 必须在 1 到 " + MAX_ITEMS + " 之间");
    }
    StringJoiner joiner = new StringJoiner(" | ", label + "=", "");
    values.stream().limit(maxItems).map(this::limit).forEach(joiner::add);
    return joiner.toString();
  }

  private String limit(String value) {
    if (value == null) return "";
    return value.length() <= 1_000 ? value : value.substring(0, 1_000);
  }

  private String execute(String toolName, java.util.function.Supplier<String> operation) {
    long startedAt = System.nanoTime();
    try {
      policy.authorize(toolName);
      String result = operation.get();
      record(toolName, "SUCCEEDED", startedAt);
      return result;
    } catch (RuntimeException error) {
      record(toolName, "FAILED", startedAt);
      throw error;
    }
  }

  private void record(String toolName, String status, long startedAt) {
    if (auditSink != null) auditSink.record(toolName, status, (System.nanoTime() - startedAt) / 1_000_000);
  }
}
