package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.AgentRunAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunAuditServiceTest {
  @Mock private AgentRunAuditRepository repository;

  @Test
  void shouldRecordGoogleAdkRuntimeMetadataWithoutPersistingInput() {
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AgentRunAuditEntity run = new AgentRunAuditService(repository).start(
        new MultiRoundInterviewEntity(), "ROUND_START", "sensitive resume text",
        "GOOGLE_ADK", "1.8.0", "mr-run-12-34", "interview-planning-v1");

    assertThat(run.getRuntimeType()).isEqualTo("GOOGLE_ADK");
    assertThat(run.getRuntimeVersion()).isEqualTo("1.8.0");
    assertThat(run.getRuntimeSessionId()).isEqualTo("mr-run-12-34");
    assertThat(run.getInputHash()).doesNotContain("sensitive resume text");
  }
}
