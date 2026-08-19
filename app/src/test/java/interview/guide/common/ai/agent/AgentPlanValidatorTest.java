package interview.guide.common.ai.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentPlanValidatorTest {
  private final AgentPlanValidator validator = new AgentPlanValidator();

  @Test @DisplayName("合法的同轮计划通过校验")
  void shouldAcceptLegalPlan() {
    assertThatCode(() -> validator.validate(new InterviewPlan(1, "ALGORITHM", 1, List.of("算法"), 1))).doesNotThrowAnyException();
  }

  @Test @DisplayName("拒绝非法轮次、超过两次追问及超出六步预算")
  void shouldRejectPlanOutsidePolicy() {
    assertThatThrownBy(() -> validator.validate(new InterviewPlan(2, "ALGORITHM", 1, List.of("算法"), 1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validate(new InterviewPlan(1, "ALGORITHM", 3, List.of("算法"), 1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validate(new InterviewPlan(1, "ALGORITHM", 1, List.of("算法"), 7))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test @DisplayName("计划只能使用当前上下文允许的分类且不能改写当前轮次")
  void shouldRejectPlanOutsideContextScope() {
    InterviewPlanningContext context = new InterviewPlanningContext("resume", "jd", "java-backend",
        "mid", 2, "TECHNICAL_1", List.of("java-core", "concurrency"), List.of(), List.of(),
        List.of(), 3, 2);

    assertThatCode(() -> validator.validate(new InterviewPlan(2, "TECHNICAL_1", 1,
        List.of("java-core"), 2), AgentBudget.DEFAULT, context)).doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validate(new InterviewPlan(2, "TECHNICAL_1", 1,
        List.of("invented-category"), 2), AgentBudget.DEFAULT, context))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validate(new InterviewPlan(3, "TECHNICAL_2", 1,
        List.of("java-core"), 2), AgentBudget.DEFAULT, context))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
