package interview.guide.common.ai.adk;

import com.google.adk.tools.Annotations.Schema;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import java.util.List;
import java.util.StringJoiner;

/** Tool set is bound to one already-sanitized context; it never reads a repository. */
final class AdkInterviewReadTools {
  private static final int MAX_ITEMS = 20;
  private final InterviewPlanningContext context;
  private final int maxCalls;
  private int calls;

  AdkInterviewReadTools(InterviewPlanningContext context, int maxCalls) {
    this.context = context;
    if (maxCalls < 1) throw new IllegalArgumentException("Tool 调用上限必须大于 0");
    this.maxCalls = maxCalls;
  }

  @Schema(name = "read_interview_snapshot", description = "Read the sanitized current interview snapshot.")
  public String readInterviewSnapshot() {
    return allowed("read_interview_snapshot", "skill=" + context.skillId() + ", difficulty=" + context.difficulty()
        + ", resume=" + limit(context.resumeSnapshot()) + ", jd=" + limit(context.jdSnapshot()));
  }

  @Schema(name = "read_prior_answers", description = "Read bounded answers from prior nodes only.")
  public String readPriorAnswers(@Schema(name = "maxItems", description = "1 to 20") int maxItems) {
    return allowed("read_prior_answers", join("answers", context.previousAnswers(), maxItems));
  }

  @Schema(name = "read_round_reports", description = "Read bounded reports from completed nodes only.")
  public String readRoundReports(@Schema(name = "maxItems", description = "1 to 20") int maxItems) {
    return allowed("read_round_reports", join("reports", context.priorRoundReportSummaries(), maxItems));
  }

  @Schema(name = "read_question_coverage", description = "Read current node coverage and budget, without question answers.")
  public String readQuestionCoverage() {
    return allowed("read_question_coverage", "node=" + context.roundType() + ", questionLimit=" + context.questionLimit()
        + ", followUpLimit=" + context.followUpLimit());
  }

  private String allowed(String name, String result) {
    if (++calls > maxCalls) throw new IllegalStateException("ADK Tool 调用超过预算");
    return result;
  }
  private String join(String label, List<String> values, int maxItems) {
    if (maxItems < 1 || maxItems > MAX_ITEMS) throw new IllegalArgumentException("maxItems 必须在 1 到 20");
    StringJoiner joiner = new StringJoiner(" | ", label + "=", "");
    values.stream().limit(maxItems).map(this::limit).forEach(joiner::add);
    return joiner.toString();
  }
  private String limit(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 1_000)); }
}
