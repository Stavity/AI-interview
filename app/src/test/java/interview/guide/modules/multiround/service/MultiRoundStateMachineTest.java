package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MultiRoundStateMachineTest {
  @Test
  @DisplayName("默认四轮按算法、两轮技术和 HR 顺序创建")
  void shouldCreateDefaultFourRoundOrder() {
    assertThat(MultiRoundStateMachine.defaultRounds()).containsExactly("ALGORITHM", "TECHNICAL_1", "TECHNICAL_2", "HR");
  }

  @Test
  @DisplayName("拒绝跳过算法首轮或将 HR 放在非末位的计划")
  void shouldRejectInvalidRoundOrder() {
    assertThatThrownBy(() -> MultiRoundStateMachine.validatePlan(List.of("TECHNICAL_1", "HR"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MultiRoundStateMachine.validatePlan(List.of("ALGORITHM", "HR", "TECHNICAL_1"))).isInstanceOf(IllegalArgumentException.class);
  }
}
