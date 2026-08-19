package interview.guide.common.ai.adk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdkToolPolicyTest {
  @Test
  void shouldRejectToolOutsideInterviewReadWhitelist() {
    AdkToolPolicy policy = new AdkToolPolicy(4);

    assertThatThrownBy(() -> policy.authorize("shell_execute"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在白名单");
  }

  @Test
  void shouldRejectToolCallsBeyondBudget() {
    AdkToolPolicy policy = new AdkToolPolicy(1);
    policy.authorize("read_interview_snapshot");

    assertThatThrownBy(() -> policy.authorize("read_prior_answers"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("调用次数");
  }
}
