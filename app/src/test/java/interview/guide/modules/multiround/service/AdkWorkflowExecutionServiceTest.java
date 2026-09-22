package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdkWorkflowExecutionServiceTest {
  @Mock private AgentRunAuditService audit;
  @Mock private AgentRunStepAuditService steps;

  @Test void retriesOnceThenRecordsSuccess() {
    AgentRunAuditEntity run = new AgentRunAuditEntity(); when(audit.start(any(), any(), any())).thenReturn(run);
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    String result = new AdkWorkflowExecutionService(audit, steps).execute(new MultiRoundInterviewEntity(), "CHECKPOINT_MUTATION", "v=3", () -> {
      if (calls.incrementAndGet() == 1) throw new IllegalStateException("transient");
      return "ok";
    });
    assertThat(result).isEqualTo("ok"); verify(audit).succeeded(run, "runtime=GOOGLE_ADK;attempt=2");
  }

  @Test void exhaustsRetryAndRecordsFailure() {
    AgentRunAuditEntity run = new AgentRunAuditEntity(); when(audit.start(any(), any(), any())).thenReturn(run);
    assertThatThrownBy(() -> new AdkWorkflowExecutionService(audit, steps).execute(new MultiRoundInterviewEntity(), "INITIAL_WORKFLOW", "x", () -> { throw new IllegalStateException("down"); }))
        .isInstanceOf(IllegalStateException.class);
    verify(audit).failed(run, "ADK_RETRY_EXHAUSTED"); verify(steps, times(4)).record(any(), anyInt(), anyString(), any(), any(), anyLong(), anyString());
  }
}
