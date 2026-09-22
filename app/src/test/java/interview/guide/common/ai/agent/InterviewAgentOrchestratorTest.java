package interview.guide.common.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.adk.AdkInterviewPlanner;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.service.AgentRunAuditService;
import interview.guide.modules.multiround.service.AgentRunStepAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewAgentOrchestratorTest {
  @Mock private LlmProviderRegistry registry;
  @Mock private StructuredOutputInvoker outputInvoker;
  @Mock private AgentRunAuditService auditService;
  @Mock private AgentRunStepAuditService stepAuditService;
  @Mock private PromptSanitizer promptSanitizer;
  @Mock private AdkInterviewPlanner adkInterviewPlanner;

  @Test @DisplayName("模型不可用时返回固定轮次的降级计划")
  void shouldFallbackWhenModelUnavailable() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setCurrentRoundNo(1); interview.setProviderId("provider-a");
    AgentRunAuditEntity audit = new AgentRunAuditEntity(); audit.setId(1L);
    when(auditService.start(any(), any(), any())).thenReturn(audit);
    when(promptSanitizer.sanitize("safe context")).thenReturn("safe context");
    when(registry.getPlainChatClient("provider-a")).thenThrow(new IllegalStateException("offline"));
    AgentRuntimeProperties runtime = new AgentRuntimeProperties();
    runtime.setRuntime(AgentRuntimeProperties.Runtime.LEGACY);
    InterviewPlan plan = new InterviewAgentOrchestrator(registry, outputInvoker, new AgentPlanValidator(), auditService,
        stepAuditService, promptSanitizer, adkInterviewPlanner, runtime).plan(interview, "NEXT_QUESTION", "safe context");
    assertThat(plan.roundNo()).isEqualTo(2); assertThat(plan.roundType()).isEqualTo("TECHNICAL_1"); assertThat(plan.followUpCount()).isZero();
  }
}
