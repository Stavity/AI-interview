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
}
