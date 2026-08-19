package interview.guide.common.ai.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentBudgetTest {

  @Test
  @DisplayName("默认预算值符合 TRD 要求")
  void defaultBudgetShouldMatchTrdSpecs() {
    AgentBudget budget = AgentBudget.DEFAULT;
    assertThatCode(() -> budget.validateStepCount(1)).doesNotThrowAnyException();
    assertThatCode(() -> budget.validateStepCount(6)).doesNotThrowAnyException();
    assertThatCode(() -> budget.validateFollowUpCount(0)).doesNotThrowAnyException();
    assertThatCode(() -> budget.validateFollowUpCount(2)).doesNotThrowAnyException();
    assertThatCode(() -> budget.validateInputLength("a".repeat(12000))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("超出预算的步骤数和追问次数被拒绝")
  void shouldRejectOutOfBudgetValues() {
    AgentBudget budget = AgentBudget.DEFAULT;
    assertThatThrownBy(() -> budget.validateStepCount(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget.validateStepCount(7)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget.validateFollowUpCount(3)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> budget.validateFollowUpCount(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("输入超长被拒绝")
  void shouldRejectOversizedInput() {
    AgentBudget budget = AgentBudget.DEFAULT;
    assertThatThrownBy(() -> budget.validateInputLength("a".repeat(12001)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
