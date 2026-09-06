package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowEventEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowEventRepository;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowInstanceRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.List;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the executable workflow before a candidate can enter a round.
 * The static definition is deliberately small: all mutable interview facts stay in the domain tables.
 */
@Service
@RequiredArgsConstructor
public class MultiRoundWorkflowService {
  static final String STATIC_DEFINITION_VERSION = "static-interview-v1";
  private final MultiRoundWorkflowInstanceRepository workflowRepository;
  private final MultiRoundWorkflowEventRepository eventRepository;
  private final MultiRoundRepository roundRepository;
  private final ObjectMapper objectMapper;
  private final AgentRuntimeProperties runtimeProperties;

  @Transactional
  public void initialize(MultiRoundInterviewEntity interview, List<MultiRoundEntity> rounds) {
    if (workflowRepository.findByInterviewId(interview.getId()).isPresent()) return;
    MultiRoundInterviewEntity.OrchestrationMode mode = runtimeProperties.getRuntime()
        == AgentRuntimeProperties.Runtime.GOOGLE_ADK
        ? MultiRoundInterviewEntity.OrchestrationMode.GOOGLE_ADK
        : MultiRoundInterviewEntity.OrchestrationMode.STATIC;
    interview.setOrchestrationMode(mode);
    MultiRoundWorkflowInstanceEntity workflow = new MultiRoundWorkflowInstanceEntity();
    workflow.setInterview(interview);
    workflow.setExecutionMode(mode);
    workflow.setDefinitionVersion(STATIC_DEFINITION_VERSION);
    workflow.setDefinitionJson(json(new StaticDefinition(STATIC_DEFINITION_VERSION,
        rounds.stream().map(round -> new StaticRound(round.getRoundNo(), round.getRoundType().name(),
            round.getQuestionLimit())).toList())));
    workflow.setStateJson(json(new WorkflowState(0, "ROUND_READY")));
    workflow.setCheckpointKey("ROUND_1_READY");
    workflow = workflowRepository.save(workflow);
    event(workflow, "WORKFLOW_CREATED", workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void roundStarted(MultiRoundInterviewEntity interview, MultiRoundEntity round) {
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.RUNNING);
    workflow.setCheckpointKey("ROUND_" + round.getRoundNo() + "_IN_PROGRESS");
    workflow.setStateJson(json(new WorkflowState(round.getRoundNo(), "IN_PROGRESS")));
    event(workflow, "ROUND_STARTED", workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void roundCompleted(MultiRoundInterviewEntity interview, MultiRoundEntity round) {
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    workflow.setCheckpointKey("ROUND_" + round.getRoundNo() + "_COMPLETED");
    workflow.setStateJson(json(new WorkflowState(round.getRoundNo(), "ROUND_COMPLETED")));
    event(workflow, "ROUND_COMPLETED", workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void nextRoundReady(MultiRoundInterviewEntity interview, int roundNo) {
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.READY);
    workflow.setCheckpointKey("ROUND_" + roundNo + "_READY");
    workflow.setStateJson(json(new WorkflowState(roundNo - 1, "ROUND_READY")));
    event(workflow, "NEXT_ROUND_READY", workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void completed(MultiRoundInterviewEntity interview) {
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.COMPLETED);
    workflow.setCheckpointKey("INTERVIEW_COMPLETED");
    workflow.setStateJson(json(new WorkflowState(interview.getCurrentRoundNo(), "COMPLETED")));
    event(workflow, "WORKFLOW_COMPLETED", workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public MultiRoundWorkflowInstanceEntity touch(MultiRoundInterviewEntity interview) {
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    if (workflow.getStatus() == MultiRoundWorkflowInstanceEntity.Status.PAUSED) {
      workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.RUNNING);
      event(workflow, "WORKFLOW_RESUMED", workflow.getCheckpointKey(), workflow.getStateJson());
    }
    if (workflow.getStatus() != MultiRoundWorkflowInstanceEntity.Status.COMPLETED) {
      workflow.setLeaseUntil(java.time.LocalDateTime.now().plus(45, ChronoUnit.SECONDS));
    }
    return workflow;
  }

  /** Called when a historical session is opened; timeout is a normal pause, never an ADK error. */
  @Transactional
  public MultiRoundWorkflowInstanceEntity recover(MultiRoundInterviewEntity interview) {
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    pauseIfExpired(workflow, java.time.LocalDateTime.now());
    return workflow;
  }

  @Transactional
  public int pauseExpiredWorkflows() {
    var expired = workflowRepository.findByStatusAndLeaseUntilBefore(
        MultiRoundWorkflowInstanceEntity.Status.RUNNING, java.time.LocalDateTime.now());
    expired.forEach(workflow -> pauseIfExpired(workflow, java.time.LocalDateTime.now()));
    return expired.size();
  }

  private void pauseIfExpired(MultiRoundWorkflowInstanceEntity workflow, java.time.LocalDateTime now) {
    if (workflow.getStatus() == MultiRoundWorkflowInstanceEntity.Status.RUNNING
        && workflow.getLeaseUntil() != null && workflow.getLeaseUntil().isBefore(now)) {
      workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.PAUSED);
      event(workflow, "LEASE_EXPIRED", workflow.getCheckpointKey(), workflow.getStateJson());
    }
  }

  @Transactional
  public MultiRoundWorkflowInstanceEntity workflow(MultiRoundInterviewEntity interview) {
    return workflowRepository.findByInterviewId(interview.getId()).orElseGet(() -> {
      // Migration-safe backfill for interview records created before workflow persistence existed.
      initialize(interview, roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId()));
      return workflowRepository.findByInterviewId(interview.getId())
          .orElseThrow(() -> new IllegalStateException("多轮面试缺少持久化工作流: " + interview.getId()));
    });
  }

  /** Static/fallback modes never call an LLM for a transition. */
  public boolean usesStaticExecutor(MultiRoundInterviewEntity interview) {
    return interview.getOrchestrationMode() != MultiRoundInterviewEntity.OrchestrationMode.GOOGLE_ADK;
  }

  /** A fallback is irreversible for this interview, preventing mixed-policy recovery. */
  @Transactional
  public void fallBackToStatic(MultiRoundInterviewEntity interview, String reason) {
    if (interview.getOrchestrationMode() != MultiRoundInterviewEntity.OrchestrationMode.GOOGLE_ADK) return;
    interview.setOrchestrationMode(MultiRoundInterviewEntity.OrchestrationMode.STATIC_FALLBACK);
    MultiRoundWorkflowInstanceEntity workflow = workflow(interview);
    workflow.setExecutionMode(MultiRoundInterviewEntity.OrchestrationMode.STATIC_FALLBACK);
    event(workflow, "STATIC_FALLBACK", workflow.getCheckpointKey(), "{\"reason\":\"" + reason + "\"}");
  }

  private void event(MultiRoundWorkflowInstanceEntity workflow, String type, String checkpoint, String payload) {
    MultiRoundWorkflowEventEntity event = new MultiRoundWorkflowEventEntity();
    event.setWorkflow(workflow); event.setEventType(type); event.setCheckpointKey(checkpoint); event.setPayloadJson(payload);
    eventRepository.save(event);
  }
  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch (JsonProcessingException error) { throw new IllegalStateException("无法序列化多轮工作流", error); }
  }
  record StaticDefinition(String version, List<StaticRound> rounds) {}
  record StaticRound(int roundNo, String roundType, int questionLimit) {}
  record WorkflowState(int completedRoundNo, String phase) {}
}
