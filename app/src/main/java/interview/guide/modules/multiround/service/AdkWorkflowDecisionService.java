package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowDecisionEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowDecisionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checkpoint-level idempotency for ADK orchestration, independent of HTTP idempotency keys. */
@Service
@RequiredArgsConstructor
public class AdkWorkflowDecisionService {
  private static final String PLAN_DECISION = "ROUND_PLAN";
  private final MultiRoundWorkflowService workflowService;
  private final MultiRoundWorkflowDecisionRepository decisionRepository;
  private final ObjectMapper objectMapper;
  private final AgentRuntimeProperties runtimeProperties;

  @Transactional(readOnly = true)
  public Optional<InterviewPlan> acceptedPlan(MultiRoundInterviewEntity interview, InterviewPlanningContext context) {
    MultiRoundWorkflowInstanceEntity workflow = workflowService.workflow(interview);
    return decisionRepository.findByWorkflowIdAndCheckpointKeyAndDecisionTypeAndStatus(
        workflow.getId(), checkpoint(context), PLAN_DECISION, MultiRoundWorkflowDecisionEntity.Status.ACCEPTED)
        .map(this::readPlan);
  }

  @Transactional
  public void acceptPlan(MultiRoundInterviewEntity interview, InterviewPlanningContext context,
      InterviewPlan plan, String adkSessionId) {
    MultiRoundWorkflowInstanceEntity workflow = workflowService.workflow(interview);
    String checkpoint = checkpoint(context);
    if (decisionRepository.findByWorkflowIdAndCheckpointKeyAndDecisionTypeAndStatus(
        workflow.getId(), checkpoint, PLAN_DECISION, MultiRoundWorkflowDecisionEntity.Status.ACCEPTED).isPresent()) return;
    MultiRoundWorkflowDecisionEntity decision = new MultiRoundWorkflowDecisionEntity();
    decision.setWorkflow(workflow); decision.setCheckpointKey(checkpoint); decision.setDecisionType(PLAN_DECISION);
    decision.setAgentName("root_interview_agent"); decision.setPolicyVersion(runtimeProperties.getPolicyVersion());
    decision.setInputHash(sha256(context.asPrompt())); decision.setDecisionJson(write(plan));
    decision.setStatus(MultiRoundWorkflowDecisionEntity.Status.ACCEPTED); decision.setAcceptedAt(LocalDateTime.now());
    decisionRepository.save(decision);
    workflow.setAdkSessionId(adkSessionId);
  }

  private String checkpoint(InterviewPlanningContext context) { return "ROUND_" + context.roundNo() + "_PLAN"; }
  private InterviewPlan readPlan(MultiRoundWorkflowDecisionEntity decision) {
    try { return objectMapper.readValue(decision.getDecisionJson(), InterviewPlan.class); }
    catch (Exception error) { throw new IllegalStateException("已接受的 ADK 编排决策不可读取", error); }
  }
  private String write(InterviewPlan plan) {
    try { return objectMapper.writeValueAsString(plan); }
    catch (Exception error) { throw new IllegalStateException("无法持久化 ADK 编排决策", error); }
  }
  private String sha256(String value) {
    try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64); for (byte valueByte : bytes) result.append(String.format("%02x", valueByte)); return result.toString(); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }
}
