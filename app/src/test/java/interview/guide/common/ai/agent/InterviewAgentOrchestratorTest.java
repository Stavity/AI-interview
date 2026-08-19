package interview.guide.common.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
  @Mock private AgentRunMetrics metrics;

  @Test @DisplayName("模型不可用时返回固定轮次的降级计划")
  void shouldFallbackWhenModelUnavailable() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setCurrentRoundNo(1); interview.setProviderId("provider-a");
    AgentRunAuditEntity audit = new AgentRunAuditEntity(); audit.setId(1L);
    when(auditService.start(any(), any(), any())).thenReturn(audit);
    when(promptSanitizer.sanitize("safe context")).thenReturn("safe context");
    when(registry.getPlainChatClient("provider-a")).thenThrow(new IllegalStateException("offline"));
    InterviewPlan plan = new InterviewAgentOrchestrator(registry, outputInvoker, new AgentPlanValidator(), auditService, stepAuditService, promptSanitizer, adkInterviewPlanner, new AgentRuntimeProperties(), metrics).plan(interview, "NEXT_QUESTION", "safe context");
    assertThat(plan.roundNo()).isEqualTo(2); assertThat(plan.roundType()).isEqualTo("TECHNICAL_1"); assertThat(plan.followUpCount()).isZero();
  }

  @Test @DisplayName("Google ADK 返回的合法计划由现有领域校验后生效")
  void shouldUseValidatedGoogleAdkPlan() {
    MultiRoundInterviewEntity interview = interview();
    AgentRunAuditEntity audit = new AgentRunAuditEntity(); audit.setId(42L);
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setRuntime(AgentRuntimeProperties.Runtime.GOOGLE_ADK);
    when(promptSanitizer.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(auditService.start(any(), any(), any(), eq("GOOGLE_ADK"), eq("1.8.0"), any(), any()))
        .thenReturn(audit);
    InterviewPlan expected = new InterviewPlan(2, "TECHNICAL_1", 1, java.util.List.of("java"), 2);
    when(adkInterviewPlanner.plan(eq(interview), any(), any(AgentRunAuditEntity.class)))
        .thenReturn(new AdkInterviewPlanner.AdkPlanResult(expected, "adk-session-42"));

    InterviewPlan actual = orchestrator(properties).plan(interview, "NEXT_QUESTION", context());

    assertThat(actual).isEqualTo(expected);
    verify(auditService).attachRuntimeSession(audit, "adk-session-42");
    verify(auditService).succeeded(audit, "round=2,type=TECHNICAL_1,steps=2");
  }

  @Test @DisplayName("Google ADK 异常时不阻塞面试并回退到确定性计划")
  void shouldFallbackWhenGoogleAdkFails() {
    MultiRoundInterviewEntity interview = interview();
    AgentRunAuditEntity audit = new AgentRunAuditEntity(); audit.setId(43L);
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setRuntime(AgentRuntimeProperties.Runtime.GOOGLE_ADK);
    when(promptSanitizer.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(auditService.start(any(), any(), any(), eq("GOOGLE_ADK"), eq("1.8.0"), any(), any()))
        .thenReturn(audit);
    when(adkInterviewPlanner.plan(eq(interview), any(), any(AgentRunAuditEntity.class)))
        .thenThrow(new IllegalStateException("model unavailable"));

    InterviewPlan plan = orchestrator(properties).plan(interview, "NEXT_QUESTION", context());

    assertThat(plan).isEqualTo(new InterviewPlan(2, "TECHNICAL_1", 0, java.util.List.of("java"), 1));
    verify(auditService).failed(audit, "ADK_FALLBACK");
  }

  @Test @DisplayName("Google ADK 超时以可观测的超时原因降级")
  void shouldClassifyGoogleAdkTimeout() {
    MultiRoundInterviewEntity interview = interview();
    AgentRunAuditEntity audit = new AgentRunAuditEntity(); audit.setId(46L);
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setRuntime(AgentRuntimeProperties.Runtime.GOOGLE_ADK);
    when(promptSanitizer.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(auditService.start(any(), any(), any(), eq("GOOGLE_ADK"), eq("1.8.0"), any(), any()))
        .thenReturn(audit);
    when(adkInterviewPlanner.plan(eq(interview), any(), any(AgentRunAuditEntity.class)))
        .thenThrow(new RuntimeException(new java.util.concurrent.TimeoutException("deadline")));

    orchestrator(properties).plan(interview, "NEXT_QUESTION", context());

    verify(auditService).failed(audit, "ADK_TIMEOUT");
    verify(metrics).recordFallback("ADK_TIMEOUT", "TECHNICAL_1");
  }

  @Test @DisplayName("Shadow 模式只审计 ADK 计划，最终计划仍由 Legacy 返回")
  void shouldKeepLegacyPlanAuthoritativeInShadowMode() {
    MultiRoundInterviewEntity interview = interview();
    AgentRunAuditEntity shadowAudit = new AgentRunAuditEntity(); shadowAudit.setId(44L);
    AgentRunAuditEntity legacyAudit = new AgentRunAuditEntity(); legacyAudit.setId(45L);
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.setRuntime(AgentRuntimeProperties.Runtime.SHADOW);
    when(promptSanitizer.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    when(auditService.start(any(), any(), any(), eq("GOOGLE_ADK"), eq("1.8.0"), any(), any()))
        .thenReturn(shadowAudit);
    when(auditService.start(any(), any(), any())).thenReturn(legacyAudit);
    when(adkInterviewPlanner.plan(eq(interview), any(), any(AgentRunAuditEntity.class))).thenReturn(
        new AdkInterviewPlanner.AdkPlanResult(new InterviewPlan(2, "TECHNICAL_1", 1,
            java.util.List.of("adk-category"), 2), "adk-session-44"));
    InterviewPlan legacyPlan = new InterviewPlan(2, "TECHNICAL_1", 0,
        java.util.List.of("legacy-category"), 1);
    when(registry.getPlainChatClient("provider-a"))
        .thenReturn(org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class));
    org.mockito.Mockito.doReturn(legacyPlan).when(outputInvoker).invoke(any(), anyString(), anyString(),
        any(), any(), anyString(), anyString(), any());

    InterviewPlan actual = orchestrator(properties).plan(interview, "ROUND_START", context());

    assertThat(actual).isEqualTo(legacyPlan);
    verify(auditService).succeeded(shadowAudit, "shadow");
    verify(stepAuditService).record(eq(shadowAudit), eq(2), eq("SHADOW_COMPARE"),
        eq("adk=[adk-category]"), eq("category_match=false,legacy=[legacy-category]"), anyLong(),
        eq("SUCCEEDED"), eq("POLICY"), eq(null));
    verify(metrics).recordShadowComparison(false);
  }

  private InterviewAgentOrchestrator orchestrator(AgentRuntimeProperties properties) {
    return new InterviewAgentOrchestrator(registry, outputInvoker, new AgentPlanValidator(), auditService,
        stepAuditService, promptSanitizer, adkInterviewPlanner, properties, metrics);
  }

  private MultiRoundInterviewEntity interview() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setCurrentRoundNo(1); interview.setProviderId("provider-a");
    return interview;
  }

  private InterviewPlanningContext context() {
    return new InterviewPlanningContext("resume", "jd", "java", "MEDIUM", 2, "TECHNICAL_1",
        java.util.List.of("java", "general", "adk-category", "legacy-category"), java.util.List.of(),
        java.util.List.of(), java.util.List.of(), 3, 2);
  }
}
