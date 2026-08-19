package interview.guide.common.ai.adk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.service.AgentRunStepAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdkToolAuditSinkTest {
  @Mock private AgentRunStepAuditService stepAuditService;

  @Test
  void shouldRecordOnlySafeToolMetadata() {
    AgentRunAuditEntity run = new AgentRunAuditEntity(); run.setId(7L);
    AdkToolAuditSink sink = new AdkToolAuditSink(stepAuditService, run);

    sink.record("read_prior_answers", "SUCCEEDED", 8);

    verify(stepAuditService).record(eq(run), eq(2), eq("read_prior_answers"), eq(null),
        eq("tool_completed"), anyLong(), eq("SUCCEEDED"), eq("TOOL_CALL"), eq("tool-1"));
  }
}
