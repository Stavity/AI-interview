package interview.guide.common.ai.agent;

import java.util.List;

/** Immutable, bounded input that the Agent may use to plan one interview round. */
public record InterviewPlanningContext(
    String resumeSnapshot,
    String jdSnapshot,
    String skillId,
    String difficulty,
    int roundNo,
    String roundType,
    List<String> priorQuestionSummaries,
    List<String> previousAnswers,
    List<String> priorRoundReportSummaries,
    int questionLimit,
    int followUpLimit
) {
  public String asPrompt() {
    return """
        简历快照：%s
        岗位 JD：%s
        面试技能：%s；难度：%s。
        当前轮次：%d（%s），题目上限：%d，单题追问上限：%d。
        已问问题摘要：%s
        既往回答：%s
        已完成轮次报告：%s
        """.formatted(resumeSnapshot, jdSnapshot, skillId, difficulty, roundNo, roundType,
        questionLimit, followUpLimit, priorQuestionSummaries, previousAnswers, priorRoundReportSummaries);
  }
}
