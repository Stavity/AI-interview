package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowEventEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowEventRepository;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowInstanceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P1 durable static workflow. It deliberately has no model dependency: a static definition is
 * persisted before ADK is introduced, so recoverability and compatibility are independently
 * verifiable.
 */
@Service
@RequiredArgsConstructor
public class MultiRoundWorkflowService {
  public static final String STATIC_DEFINITION_VERSION = "static-interview-v1";

  private final MultiRoundWorkflowInstanceRepository workflowRepository;
  private final MultiRoundWorkflowEventRepository eventRepository;
  private final MultiRoundRepository roundRepository;
  private final ObjectMapper objectMapper;

  @Transactional
  public MultiRoundWorkflowInstanceEntity initializeStatic(
      MultiRoundInterviewEntity interview, List<MultiRoundEntity> rounds) {
    return workflowRepository.findByInterviewId(interview.getId()).orElseGet(() -> {
      List<MultiRoundEntity> ordered = rounds.stream()
          .sorted(java.util.Comparator.comparingInt(MultiRoundEntity::getRoundNo))
          .toList();
      ordered.forEach(this::applyStaticNodeMetadata);

      interview.setOrchestrationMode(MultiRoundInterviewEntity.OrchestrationMode.LEGACY_STATIC);
      MultiRoundWorkflowInstanceEntity workflow = new MultiRoundWorkflowInstanceEntity();
      workflow.setInterview(interview);
      workflow.setExecutionMode(MultiRoundInterviewEntity.OrchestrationMode.LEGACY_STATIC);
      workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.READY);
      workflow.setDefinitionVersion(STATIC_DEFINITION_VERSION);
      workflow.setDefinitionJson(json(new StaticWorkflowDefinition(STATIC_DEFINITION_VERSION,
          ordered.stream().map(this::nodeDefinition).toList())));
      workflow.setStateJson(json(new WorkflowState(0, "ROUND_READY")));
      workflow.setCheckpointKey("NODE_" + ordered.getFirst().getNodeKey() + "_READY");
      workflow.setWorkflowRevision(0);
      MultiRoundWorkflowInstanceEntity saved = workflowRepository.save(workflow);
      event(saved, "WORKFLOW_CREATED", "workflow-created", saved.getCheckpointKey(), saved.getDefinitionJson());
      return saved;
    });
  }

  /** Migration-safe lazy backfill for a historical interview read after the new Flyway migration. */
  @Transactional
  public MultiRoundWorkflowInstanceEntity workflowFor(MultiRoundInterviewEntity interview) {
    return workflowRepository.findByInterviewId(interview.getId())
        .orElseGet(() -> initializeStatic(interview,
            roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId())));
  }

  @Transactional
  public WorkflowView view(MultiRoundInterviewEntity interview) {
    MultiRoundWorkflowInstanceEntity workflow = workflowFor(interview);
    List<NodeView> nodes = roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId()).stream()
        .sorted(java.util.Comparator.comparingInt(MultiRoundEntity::getDisplayOrder))
        .map(round -> new NodeView(round.getId(), round.getNodeKey(), round.getNodeType(),
            round.getDisplayOrder(), round.getStatus().name(), round.getQuestionLimit(), round.getOrigin().name()))
        .toList();
    return new WorkflowView(workflow.getWorkflowRevision(), workflow.getExecutionMode().name(),
        workflow.getStatus().name(), workflow.getCheckpointKey(), nodes);
  }

  @Transactional
  public void nodeStarted(MultiRoundInterviewEntity interview, MultiRoundEntity node) {
    MultiRoundWorkflowInstanceEntity workflow = workflowFor(interview);
    workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.RUNNING);
    workflow.setCheckpointKey("NODE_" + nodeKey(node) + "_IN_PROGRESS");
    workflow.setStateJson(json(new WorkflowState(node.getRoundNo(), "IN_PROGRESS")));
    workflow.setWorkflowRevision(workflow.getWorkflowRevision() + 1);
    event(workflow, "NODE_STARTED", "node-started:" + nodeKey(node) + ":" + workflow.getWorkflowRevision(),
        workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void nodeCompleted(MultiRoundInterviewEntity interview, MultiRoundEntity node) {
    MultiRoundWorkflowInstanceEntity workflow = workflowFor(interview);
    workflow.setCheckpointKey("NODE_" + nodeKey(node) + "_COMPLETED");
    workflow.setStateJson(json(new WorkflowState(node.getRoundNo(), "NODE_COMPLETED")));
    workflow.setWorkflowRevision(workflow.getWorkflowRevision() + 1);
    event(workflow, "NODE_COMPLETED", "node-completed:" + nodeKey(node) + ":" + workflow.getWorkflowRevision(),
        workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void nextNodeReady(MultiRoundInterviewEntity interview, MultiRoundEntity node) {
    MultiRoundWorkflowInstanceEntity workflow = workflowFor(interview);
    workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.READY);
    workflow.setCheckpointKey("NODE_" + nodeKey(node) + "_READY");
    workflow.setStateJson(json(new WorkflowState(node.getRoundNo() - 1, "NODE_READY")));
    workflow.setWorkflowRevision(workflow.getWorkflowRevision() + 1);
    event(workflow, "NODE_READY", "node-ready:" + nodeKey(node) + ":" + workflow.getWorkflowRevision(),
        workflow.getCheckpointKey(), workflow.getStateJson());
  }

  @Transactional
  public void completed(MultiRoundInterviewEntity interview) {
    MultiRoundWorkflowInstanceEntity workflow = workflowFor(interview);
    workflow.setStatus(MultiRoundWorkflowInstanceEntity.Status.COMPLETED);
    workflow.setCheckpointKey("WORKFLOW_COMPLETED");
    workflow.setStateJson(json(new WorkflowState(interview.getCurrentRoundNo(), "COMPLETED")));
    workflow.setWorkflowRevision(workflow.getWorkflowRevision() + 1);
    event(workflow, "WORKFLOW_COMPLETED", "workflow-completed:" + workflow.getWorkflowRevision(),
        workflow.getCheckpointKey(), workflow.getStateJson());
  }

  private void applyStaticNodeMetadata(MultiRoundEntity round) {
    if (round.getNodeKey() == null || round.getNodeKey().isBlank()) {
      round.setNodeKey("static-%02d-%s".formatted(round.getRoundNo(),
          round.getRoundType().name().toLowerCase(java.util.Locale.ROOT)));
    }
    if (round.getNodeType() == null || round.getNodeType().isBlank()) round.setNodeType(round.getRoundType().name());
    if (round.getDisplayOrder() < 1) round.setDisplayOrder(round.getRoundNo());
    if (round.getOrigin() == null) round.setOrigin(MultiRoundEntity.Origin.STATIC);
  }

  private NodeDefinition nodeDefinition(MultiRoundEntity round) {
    return new NodeDefinition(round.getNodeKey(), round.getNodeType(), round.getDisplayOrder(),
        round.getQuestionLimit(), round.getDifficultyBand(), round.getCoverageJson(), round.getOrigin().name());
  }

  private String nodeKey(MultiRoundEntity node) {
    return node.getNodeKey() == null || node.getNodeKey().isBlank()
        ? "legacy-round-" + node.getRoundNo() : node.getNodeKey();
  }

  private void event(MultiRoundWorkflowInstanceEntity workflow, String type, String key, String checkpoint,
      String payload) {
    MultiRoundWorkflowEventEntity event = new MultiRoundWorkflowEventEntity();
    event.setWorkflow(workflow);
    event.setEventType(type);
    event.setEventKey(key);
    event.setCheckpointKey(checkpoint);
    event.setPayloadJson(payload);
    eventRepository.save(event);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("无法序列化多轮面试工作流", exception);
    }
  }

  record StaticWorkflowDefinition(String version, List<NodeDefinition> nodes) {}
  record NodeDefinition(String nodeKey, String nodeType, int displayOrder, int questionLimit,
                        String difficultyBand, String coverageJson, String origin) {}
  record WorkflowState(int completedRoundNo, String phase) {}
  public record NodeView(Long id, String nodeKey, String nodeType, int displayOrder, String status,
                         int questionLimit, String origin) {}
  public record WorkflowView(long workflowRevision, String executionMode, String status,
                             String checkpointKey, List<NodeView> nodes) {}
}
