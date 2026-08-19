package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.repository.AgentRunStepAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunStepAuditServiceTest {
  @Mock private AgentRunStepAuditRepository repository;
  @Test @DisplayName("步骤审计只保存受限摘要")
  void shouldStoreBoundedSummaries() {
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AgentRunAuditEntity run = new AgentRunAuditEntity();
    String large = "x".repeat(1000);
    var step = new AgentRunStepAuditService(repository).record(run, 1, "PLAN", large, large, 12, "SUCCEEDED");
    assertThat(step.getInputSummary()).hasSize(500); assertThat(step.getOutputSummary()).hasSize(500);
  }

  @Test @DisplayName("步骤审计保留安全的事件类型和工具调用关联")
  void shouldStoreEventMetadata() {
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var step = new AgentRunStepAuditService(repository).record(new AgentRunAuditEntity(), 2,
        "read_prior_answers", "maxItems=3", "answers=2", 8, "SUCCEEDED", "TOOL_CALL", "call-7");

    assertThat(step.getEventType()).isEqualTo("TOOL_CALL");
    assertThat(step.getToolCallId()).isEqualTo("call-7");
  }
}
