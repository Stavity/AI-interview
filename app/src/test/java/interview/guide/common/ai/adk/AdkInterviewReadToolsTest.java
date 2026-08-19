package interview.guide.common.ai.adk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.ai.agent.InterviewPlanningContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdkInterviewReadToolsTest {
  private final InterviewPlanningContext context = new InterviewPlanningContext(
      "Candidate project: cache migration", "Java platform", "java-backend", "mid", 2,
      "TECHNICAL_1", List.of("java-core"), List.of("Explain cache invalidation"), List.of("Used delayed double delete"),
      List.of("round one completed"), 2, 2);

  @Test
  void shouldExposeOnlyBoundedCurrentInterviewContext() {
    AdkInterviewReadTools tools = new AdkInterviewReadTools(context, new AdkToolPolicy(4));

    assertThat(tools.readPriorAnswers(1)).contains("Used delayed double delete");
    assertThat(tools.readRoundReports(1)).contains("round one completed");
    assertThat(tools.readQuestionCoverage()).contains("TECHNICAL_1");
  }

  @Test
  void shouldRejectInvalidToolParameter() {
    AdkInterviewReadTools tools = new AdkInterviewReadTools(context, new AdkToolPolicy(4));

    assertThatThrownBy(() -> tools.readPriorAnswers(21))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxItems");
  }
}
