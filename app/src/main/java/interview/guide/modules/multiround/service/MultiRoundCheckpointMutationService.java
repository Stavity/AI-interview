package interview.guide.modules.multiround.service;

import interview.guide.common.ai.adk.AdkInterviewPlanner;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Runs outside domain write transactions; a rejected proposal simply leaves the future graph intact. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundCheckpointMutationService {
  private final AgentRuntimeProperties runtimeProperties;
  private final AdkInterviewPlanner planner;
  private final MultiRoundWorkflowDecisionService decisionService;
  private final MultiRoundWorkflowService workflowService;
  private final MultiRoundPlanningContextFactory contextFactory;
  private final AdkWorkflowExecutionService executionService;

  public void mutateBeforeStart(MultiRoundInterviewEntity interview, MultiRoundEntity next) {
    if (runtimeProperties.getRuntime() != AgentRuntimeProperties.Runtime.GOOGLE_ADK
        || interview.getOrchestrationMode() != MultiRoundInterviewEntity.OrchestrationMode.GOOGLE_ADK) return;
    try {
      long revision = workflowService.workflowFor(interview).getWorkflowRevision();
      var context = contextFactory.create(interview, next);
      var decision = executionService.execute(interview, "CHECKPOINT_MUTATION", "revision=" + revision,
          () -> planner.planCheckpointMutation(interview, context, revision));
      if (!decisionService.applyCheckpointMutation(interview, decision, runtimeProperties.getPolicyVersion())) {
        log.info("checkpoint 决策未被接受，保留既有未来节点: interviewId={}", interview.getId());
      }
    } catch (Exception error) {
      log.warn("checkpoint ADK 不可用，保留既有未来节点: interviewId={}, error={}", interview.getId(), error.getMessage());
    }
  }
}
