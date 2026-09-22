package interview.guide.modules.multiround.service;

import interview.guide.common.ai.adk.AdkInterviewPlanner;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Executes the initial model call outside the bootstrap transaction, before the first node starts. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundWorkflowInitializationService {
  private final AgentRuntimeProperties runtimeProperties;
  private final AdkInterviewPlanner adkInterviewPlanner;
  private final MultiRoundWorkflowDecisionService decisionService;
  private final MultiRoundWorkflowService workflowService;
  private final AdkWorkflowExecutionService executionService;

  public void initializeNewInterview(MultiRoundInterviewEntity interview) {
    if (runtimeProperties.getRuntime() != AgentRuntimeProperties.Runtime.GOOGLE_ADK) return;
    try {
      InitialWorkflowDecision decision = executionService.execute(interview, "INITIAL_WORKFLOW",
          interview.getScopeFingerprint(), () -> adkInterviewPlanner.planInitialWorkflow(interview));
      boolean accepted = decisionService.applyInitialAdkDecision(interview, decision, runtimeProperties.getPolicyVersion());
      if (!accepted) workflowService.markInitialFallback(interview, "POLICY_REJECTED");
    } catch (Exception exception) {
      log.warn("ADK 初始工作流不可用，使用静态降级: interviewId={}, reason={}", interview.getId(), exception.getMessage());
      workflowService.markInitialFallback(interview, shortReason(exception));
    }
  }

  private String shortReason(Exception exception) {
    String message = exception.getMessage();
    return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(128, message.length()));
  }
}
