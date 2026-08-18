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
}
