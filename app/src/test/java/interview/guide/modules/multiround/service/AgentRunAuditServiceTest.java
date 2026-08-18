package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.AgentRunAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunAuditServiceTest {
  @Mock private AgentRunAuditRepository repository;
  @Test @DisplayName("审计记录只保存输入哈希而非原始输入")
  void shouldStoreInputHashOnly() {
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    var run = new AgentRunAuditService(repository).start(interview, "NEXT_QUESTION", "resume and answer content");
    assertThat(run.getInputHash()).hasSize(64);
    assertThat(run.getOutputJson()).isNull();
  }
}
