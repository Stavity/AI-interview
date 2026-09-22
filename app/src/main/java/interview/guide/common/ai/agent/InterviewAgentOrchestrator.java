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

  public InterviewPlan plan(MultiRoundInterviewEntity interview, String trigger, InterviewPlanningContext context) {
    if (runtimeProperties.getRuntime() == AgentRuntimeProperties.Runtime.GOOGLE_ADK) {
      return planWithAdk(interview, trigger, context);
    }
    return plan(interview, trigger, context.asPrompt());
  }

  private InterviewPlan planWithAdk(MultiRoundInterviewEntity interview, String trigger, InterviewPlanningContext context) {
    AgentBudget budget = AgentBudget.DEFAULT;
    String safeContext = boundedContext(context.asPrompt(), budget);
    var audit = auditService.start(interview, trigger, safeContext);
    long startedAt = System.nanoTime();
    try {
      InterviewPlan plan = adkInterviewPlanner.plan(interview, context);
      budget.validateOutputLength(plan.toString());
      validator.validate(plan, budget);
      stepAuditService.record(audit, 1, "ADK_ROOT_WORKFLOW", "runtime=GOOGLE_ADK",
          "round=" + plan.roundNo() + ",type=" + plan.roundType(), elapsedMs(startedAt), "SUCCEEDED");
      auditService.succeeded(audit, "GOOGLE_ADK");
      return plan;
    } catch (Exception error) {
      stepAuditService.record(audit, 1, "ADK_ROOT_WORKFLOW", "runtime=GOOGLE_ADK", "fallback",
          elapsedMs(startedAt), "FAILED");
      auditService.failed(audit, error instanceof java.util.concurrent.TimeoutException ? "ADK_TIMEOUT" : "ADK_FALLBACK");
      return fallback(interview);
    }
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
      stepAuditService.record(audit, 1, "PLAN", "provider=" + interview.getProviderId(), output, elapsedMs(startedAt), "SUCCEEDED");
      auditService.succeeded(audit, output);
      return plan;
    } catch (Exception error) {
      stepAuditService.record(audit, 1, "PLAN", "provider=" + interview.getProviderId(), "fallback", elapsedMs(startedAt), "FAILED");
      auditService.failed(audit, "AGENT_FALLBACK"); return fallback(interview);
    }
  }
  private String boundedContext(String context, AgentBudget budget) {
    String sanitized = promptSanitizer.sanitize(context == null ? "" : context);
    budget.validateInputLength(sanitized);
    return sanitized;
  }
  private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
  private InterviewPlan fallback(MultiRoundInterviewEntity interview) { int roundNo = Math.min(4, Math.max(1, interview.getCurrentRoundNo() + 1)); String type = switch (roundNo) { case 1 -> "ALGORITHM"; case 2 -> "TECHNICAL_1"; case 3 -> "TECHNICAL_2"; default -> "HR"; }; return new InterviewPlan(roundNo, type, 0, java.util.List.of("general"), 1); }
}
