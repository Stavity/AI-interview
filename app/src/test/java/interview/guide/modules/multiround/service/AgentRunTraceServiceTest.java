package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.AgentRunStepAuditEntity;
import interview.guide.modules.multiround.repository.AgentRunAuditRepository;
import interview.guide.modules.multiround.repository.AgentRunStepAuditRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunTraceServiceTest {
  @Mock private AgentRunAuditRepository runRepository;
  @Mock private AgentRunStepAuditRepository stepRepository;

  @Test @DisplayName("轨迹只返回已脱敏的步骤摘要")
  void shouldReturnTraceWithoutRunInput() {
    AgentRunAuditEntity run = new AgentRunAuditEntity(); run.setId(7L); run.setTriggerType("NEXT_QUESTION"); run.setStatus(AgentRunAuditEntity.Status.SUCCEEDED);
    AgentRunStepAuditEntity step = new AgentRunStepAuditEntity(); step.setRun(run); step.setStepNo(1); step.setToolName("PLAN"); step.setStatus("SUCCEEDED"); step.setOutputSummary("round=2");
    when(runRepository.findByInterviewIdOrderByStartedAtDesc(3L)).thenReturn(List.of(run));
    // 改为批量取步骤后按 run 分组，避免 1+N 查询
    when(stepRepository.findByRunIdInOrderByStepNoAsc(List.of(7L))).thenReturn(List.of(step));

    var trace = new AgentRunTraceService(runRepository, stepRepository).list(3L);

    assertThat(trace).singleElement().satisfies(item -> {
      assertThat(item.triggerType()).isEqualTo("NEXT_QUESTION");
      assertThat(item.steps()).singleElement().extracting(AgentRunTraceService.StepTrace::outputSummary).isEqualTo("round=2");
    });
  }
}
