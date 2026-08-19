package interview.guide.common.ai.adk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reactivex.rxjava3.core.Flowable;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class AdkInterviewPlannerTimeoutTest {
  @Test
  void shouldStopAnAdkEventStreamAtTheConfiguredPlanningDeadline() {
    assertThatThrownBy(() -> AdkInterviewPlanner.awaitFinalEvent(Flowable.never(), 10))
        .hasCauseInstanceOf(TimeoutException.class);
  }
}
