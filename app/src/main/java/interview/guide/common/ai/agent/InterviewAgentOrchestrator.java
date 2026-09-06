package interview.guide.common.ai.agent;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.adk.AdkInterviewPlanner;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.service.AgentRunAuditService;
import interview.guide.modules.multiround.service.AgentRunStepAuditService;
import interview.guide.modules.multiround.service.AdkWorkflowDecisionService;
import interview.guide.modules.multiround.service.MultiRoundWorkflowService;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class InterviewAgentOrchestrator {
  private static final int MAX_CONTEXT_LENGTH = 12_000;
  private final LlmProviderRegistry registry; private final StructuredOutputInvoker outputInvoker;
  private final AgentPlanValidator validator; private final AgentRunAuditService auditService;
  private final AgentRunStepAuditService stepAuditService;
  private final PromptSanitizer promptSanitizer;
  private final AdkInterviewPlanner adkInterviewPlanner;
  private final AgentRuntimeProperties runtimeProperties;
  private final AgentRunMetrics metrics;
  private final AdkWorkflowDecisionService adkDecisionService;
  private final MultiRoundWorkflowService workflowService;

  public InterviewPlan plan(MultiRoundInterviewEntity interview, String trigger, InterviewPlanningContext context) {
    if (runtimeProperties.getRuntime() == AgentRuntimeProperties.Runtime.GOOGLE_ADK) {
      return planWithAdk(interview, trigger, context);
    }
    if (runtimeProperties.getRuntime() == AgentRuntimeProperties.Runtime.SHADOW) {
      ShadowRun shadowRun = runAdkShadow(interview, trigger, context);
      InterviewPlan legacyPlan = plan(interview, trigger, context.asPrompt());
      recordShadowComparison(shadowRun, legacyPlan);
      return legacyPlan;
    }
    return plan(interview, trigger, context.asPrompt());
  }

  public InterviewPlan plan(MultiRoundInterviewEntity interview, String trigger, String context) {
    AgentBudget budget = AgentBudget.DEFAULT;
    String safeContext = boundedContext(context, budget);
    var audit = auditService.start(interview, trigger, safeContext);
    long startedAt = System.nanoTime();
    try {
      var converter = new BeanOutputConverter<>(InterviewPlan.class);
      InterviewPlan plan = outputInvoker.invoke(registry.getPlainChatClient(interview.getProviderId()), "你是受控面试规划器。不能调用工具、不能写数据库、不能执行代码。只生成当前或下一轮的结构化计划。\n" + converter.getFormat(), promptSanitizer.wrapWithDelimiters("agent-context", safeContext), converter, ErrorCode.AI_SERVICE_ERROR, "Agent 计划生成失败: ", "[multi-round-agent] ", LoggerFactory.getLogger(InterviewAgentOrchestrator.class));
      budget.validateOutputLength(plan.toString());
      validator.validate(plan, budget);
      String output = "round=" + plan.roundNo() + ",type=" + plan.roundType() + ",steps=" + plan.stepCount();
      stepAuditService.record(audit, 1, "PLAN", "provider=" + interview.getProviderId(), output,
          elapsedMs(startedAt), "SUCCEEDED", "MODEL", null);
      auditService.succeeded(audit, output);
      metrics.recordRun("LEGACY", "SUCCEEDED", trigger, interview.getProviderId(), elapsedMs(startedAt));
      return plan;
    } catch (Exception error) {
      stepAuditService.record(audit, 1, "PLAN", "provider=" + interview.getProviderId(), "fallback",
          elapsedMs(startedAt), "FAILED", "POLICY", null);
      auditService.failed(audit, "AGENT_FALLBACK");
      metrics.recordRun("LEGACY", "FAILED", trigger, interview.getProviderId(), elapsedMs(startedAt));
      metrics.recordFallback("AGENT_FALLBACK", fallback(interview).roundType());
      return fallback(interview);
    }
  }
  private String boundedContext(String context, AgentBudget budget) {
    String sanitized = promptSanitizer.sanitize(context == null ? "" : context);
    budget.validateInputLength(sanitized);
    return sanitized;
  }
  private InterviewPlan planWithAdk(MultiRoundInterviewEntity interview, String trigger,
      InterviewPlanningContext context) {
    AgentBudget budget = AgentBudget.DEFAULT;
    String safeContext = boundedContext(context.asPrompt(), budget);
    metrics.recordContext("planning", safeContext.length());
    var audit = auditService.start(interview, trigger, safeContext, "GOOGLE_ADK", "1.8.0", null,
        runtimeProperties.getPolicyVersion());
    long startedAt = System.nanoTime();
    try {
      var accepted = adkDecisionService.acceptedPlan(interview, context);
      if (accepted.isPresent()) {
        InterviewPlan plan = accepted.get();
        stepAuditService.record(audit, 1, "ADK_CHECKPOINT_RESTORE", "checkpoint=ROUND_" + context.roundNo(),
            "accepted-plan", elapsedMs(startedAt), "SUCCEEDED", "POLICY", null);
        auditService.succeeded(audit, "checkpoint-restored");
        metrics.recordRun("GOOGLE_ADK", "SUCCEEDED", trigger, interview.getProviderId(), elapsedMs(startedAt));
        return plan;
      }
      var result = adkInterviewPlanner.plan(interview, context, audit);
      auditService.attachRuntimeSession(audit, result.sessionId());
      budget.validateOutputLength(result.plan().toString());
      validator.validate(result.plan(), budget, context);
      adkDecisionService.acceptPlan(interview, context, result.plan(), result.sessionId());
      String output = "round=" + result.plan().roundNo() + ",type=" + result.plan().roundType()
          + ",steps=" + result.plan().stepCount();
      stepAuditService.record(audit, 1, "ADK_RUNNER", "session=" + result.sessionId(), output,
          elapsedMs(startedAt), "SUCCEEDED", "MODEL", null);
      auditService.succeeded(audit, output);
      metrics.recordRun("GOOGLE_ADK", "SUCCEEDED", trigger, interview.getProviderId(), elapsedMs(startedAt));
      return result.plan();
    } catch (Exception error) {
      String reason = adkFailureReason(error, "ADK_FALLBACK");
      stepAuditService.record(audit, 1, "ADK_RUNNER", "runtime=GOOGLE_ADK", "fallback",
          elapsedMs(startedAt), "FAILED", "POLICY", null);
      auditService.failed(audit, reason);
      workflowService.fallBackToStatic(interview, reason);
      metrics.recordRun("GOOGLE_ADK", "FAILED", trigger, interview.getProviderId(), elapsedMs(startedAt));
      metrics.recordFallback(reason, context.roundType());
      return fallback(interview, context);
    }
  }
  private ShadowRun runAdkShadow(MultiRoundInterviewEntity interview, String trigger,
      InterviewPlanningContext context) {
    AgentBudget budget = AgentBudget.DEFAULT;
    String safeContext = boundedContext(context.asPrompt(), budget);
    metrics.recordContext("planning", safeContext.length());
    var audit = auditService.start(interview, trigger, safeContext, "GOOGLE_ADK", "1.8.0", null,
        runtimeProperties.getPolicyVersion());
    long startedAt = System.nanoTime();
    try {
      var result = adkInterviewPlanner.plan(interview, context, audit);
      auditService.attachRuntimeSession(audit, result.sessionId());
      validator.validate(result.plan(), budget, context);
      stepAuditService.record(audit, 1, "ADK_SHADOW", "session=" + result.sessionId(),
          "round=" + result.plan().roundNo() + ",type=" + result.plan().roundType(),
          elapsedMs(startedAt), "SUCCEEDED", "MODEL", null);
      auditService.succeeded(audit, "shadow");
      metrics.recordRun("GOOGLE_ADK", "SUCCEEDED", trigger, interview.getProviderId(), elapsedMs(startedAt));
      return new ShadowRun(audit, result.plan());
    } catch (Exception error) {
      String reason = adkFailureReason(error, "ADK_SHADOW_FAILED");
      stepAuditService.record(audit, 1, "ADK_SHADOW", "runtime=GOOGLE_ADK", "fallback",
          elapsedMs(startedAt), "FAILED", "POLICY", null);
      auditService.failed(audit, reason);
      metrics.recordRun("GOOGLE_ADK", "FAILED", trigger, interview.getProviderId(), elapsedMs(startedAt));
      metrics.recordFallback(reason, context.roundType());
      return null;
    }
  }
  private void recordShadowComparison(ShadowRun shadowRun, InterviewPlan legacyPlan) {
    if (shadowRun == null) return;
    boolean categoryMatch = new java.util.LinkedHashSet<>(shadowRun.plan().categoryKeys())
        .equals(new java.util.LinkedHashSet<>(legacyPlan.categoryKeys()));
    stepAuditService.record(shadowRun.audit(), 2, "SHADOW_COMPARE",
        "adk=" + shadowRun.plan().categoryKeys(),
        "category_match=" + categoryMatch + ",legacy=" + legacyPlan.categoryKeys(), 0,
        "SUCCEEDED", "POLICY", null);
    metrics.recordShadowComparison(categoryMatch);
  }
  private String adkFailureReason(Throwable error, String fallbackReason) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof java.util.concurrent.TimeoutException) return "ADK_TIMEOUT";
    }
    return fallbackReason;
  }
  private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
  private InterviewPlan fallback(MultiRoundInterviewEntity interview, InterviewPlanningContext context) {
    InterviewPlan fallback = fallback(interview);
    String category = context.allowedCategoryKeys() == null || context.allowedCategoryKeys().isEmpty()
        ? fallback.categoryKeys().getFirst() : context.allowedCategoryKeys().getFirst();
    return new InterviewPlan(fallback.roundNo(), fallback.roundType(), fallback.followUpCount(),
        java.util.List.of(category), fallback.stepCount());
  }
  private InterviewPlan fallback(MultiRoundInterviewEntity interview) { int roundNo = Math.min(4, Math.max(1, interview.getCurrentRoundNo() + 1)); String type = switch (roundNo) { case 1 -> "ALGORITHM"; case 2 -> "TECHNICAL_1"; case 3 -> "TECHNICAL_2"; default -> "HR"; }; return new InterviewPlan(roundNo, type, 0, java.util.List.of("general"), 1); }
  private record ShadowRun(interview.guide.modules.multiround.model.AgentRunAuditEntity audit,
      InterviewPlan plan) {}
}
