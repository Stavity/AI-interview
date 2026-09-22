package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowDecisionEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowDecisionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Materializes an initial RootAgent proposal only after deterministic policy validation. */
@Service
@RequiredArgsConstructor
public class MultiRoundWorkflowDecisionService {
  private final MultiRoundWorkflowService workflowService;
  private final MultiRoundWorkflowDecisionRepository decisionRepository;
  private final MultiRoundRepository roundRepository;
  private final WorkflowPolicyGate policyGate;
  private final ObjectMapper objectMapper;

  @Transactional
  public boolean applyInitialAdkDecision(MultiRoundInterviewEntity interview, InitialWorkflowDecision decision,
      String policyVersion) {
    MultiRoundWorkflowInstanceEntity workflow = workflowService.workflowFor(interview);
    String inputHash = hash(interview.getResumeSnapshot() + "\n" + interview.getJdSnapshot() + "\n" + interview.getSkillId());
    String key = "INITIAL_ADK:" + inputHash;
    if (decisionRepository.findByWorkflowIdAndDecisionKey(workflow.getId(), key).isPresent()) return false;
    try {
      List<WorkflowNodeDefinition> allowed = policyGate.validateInitial(decision);
      List<MultiRoundEntity> current = roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId());
      if (current.stream().anyMatch(node -> node.getStatus() != MultiRoundEntity.Status.PENDING)) {
        throw new IllegalStateException("已开始的节点不可被初始决策覆盖");
      }
      roundRepository.deleteAll(current);
      roundRepository.flush();
      List<MultiRoundEntity> materialized = materialize(interview, allowed);
      roundRepository.saveAll(materialized);
      workflowService.replaceInitialDefinition(interview, materialized,
          MultiRoundInterviewEntity.OrchestrationMode.GOOGLE_ADK, "ROOT_AGENT");
      audit(workflow, key, inputHash, policyVersion, MultiRoundWorkflowDecisionEntity.Status.ACCEPTED, null, decision);
      return true;
    } catch (RuntimeException exception) {
      audit(workflow, key, inputHash, policyVersion, MultiRoundWorkflowDecisionEntity.Status.REJECTED,
          reason(exception), decision);
      return false;
    }
  }

  @Transactional
  public boolean applyCheckpointMutation(MultiRoundInterviewEntity interview, WorkflowMutationDecision decision,
      String policyVersion) {
    MultiRoundWorkflowInstanceEntity workflow = workflowService.workflowFor(interview);
    String inputHash = hash(workflow.getCheckpointKey() + "\n" + workflow.getWorkflowRevision());
    String key = "CHECKPOINT_ADK:" + inputHash;
    if (decisionRepository.findByWorkflowIdAndDecisionKey(workflow.getId(), key).isPresent()) return false;
    try {
      List<MultiRoundEntity> nodes = roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId());
      java.util.Map<String, MultiRoundEntity> pending = nodes.stream()
          .filter(node -> node.getStatus() == MultiRoundEntity.Status.PENDING)
          .collect(java.util.stream.Collectors.toMap(MultiRoundEntity::getNodeKey, node -> node));
      for (WorkflowNodePatch patch : policyGate.validateMutation(decision, workflow.getWorkflowRevision(), pending.keySet())) {
        MultiRoundEntity node = pending.get(patch.nodeKey());
        node.setQuestionLimit(patch.questionLimit());
        node.setDifficultyBand(patch.difficultyBand());
        node.setCoverageJson(json(patch.coverage()));
      }
      roundRepository.saveAll(pending.values());
      workflowService.refreshDefinitionAfterMutation(interview, nodes);
      auditMutation(workflow, key, inputHash, policyVersion, MultiRoundWorkflowDecisionEntity.Status.ACCEPTED, null, decision);
      return true;
    } catch (RuntimeException exception) {
      auditMutation(workflow, key, inputHash, policyVersion, MultiRoundWorkflowDecisionEntity.Status.REJECTED,
          reason(exception), decision);
      return false;
    }
  }

  private List<MultiRoundEntity> materialize(MultiRoundInterviewEntity interview, List<WorkflowNodeDefinition> nodes) {
    return java.util.stream.IntStream.range(0, nodes.size()).mapToObj(index -> {
      WorkflowNodeDefinition spec = nodes.get(index);
      MultiRoundEntity node = new MultiRoundEntity();
      node.setInterview(interview);
      node.setRoundNo(index + 1);
      node.setRoundType(legacyType(spec.nodeType()));
      node.setQuestionLimit(spec.questionLimit());
      node.setNodeKey(spec.nodeKey());
      node.setNodeType(spec.nodeType().trim().toUpperCase(java.util.Locale.ROOT));
      node.setDisplayOrder(index + 1);
      node.setDifficultyBand(spec.difficultyBand());
      node.setCoverageJson(json(spec.coverage()));
      node.setOrigin(MultiRoundEntity.Origin.ADK);
      return node;
    }).toList();
  }

  private MultiRoundEntity.Type legacyType(String type) {
    return switch (type.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "ALGORITHM" -> MultiRoundEntity.Type.ALGORITHM;
      case "HR" -> MultiRoundEntity.Type.HR;
      case "SYSTEM_DESIGN" -> MultiRoundEntity.Type.TECHNICAL_2;
      default -> MultiRoundEntity.Type.TECHNICAL_1;
    };
  }

  private void audit(MultiRoundWorkflowInstanceEntity workflow, String key, String inputHash, String policyVersion,
      MultiRoundWorkflowDecisionEntity.Status status, String reason, InitialWorkflowDecision decision) {
    MultiRoundWorkflowDecisionEntity value = new MultiRoundWorkflowDecisionEntity();
    value.setWorkflow(workflow);
    value.setDecisionKey(key);
    value.setDecisionType("INITIAL_WORKFLOW");
    value.setAgentName("root_interview_agent");
    value.setPolicyVersion(policyVersion == null || policyVersion.isBlank() ? WorkflowPolicyGate.VERSION : policyVersion);
    value.setInputHash(inputHash);
    value.setStatus(status);
    value.setRejectionReason(reason);
    value.setDecisionJson(json(decision));
    if (status == MultiRoundWorkflowDecisionEntity.Status.ACCEPTED) value.setAcceptedAt(LocalDateTime.now());
    decisionRepository.save(value);
  }
  private void auditMutation(MultiRoundWorkflowInstanceEntity workflow, String key, String inputHash, String policyVersion,
      MultiRoundWorkflowDecisionEntity.Status status, String reason, WorkflowMutationDecision decision) {
    MultiRoundWorkflowDecisionEntity value = new MultiRoundWorkflowDecisionEntity();
    value.setWorkflow(workflow); value.setDecisionKey(key); value.setDecisionType("CHECKPOINT_MUTATION");
    value.setAgentName("root_interview_agent"); value.setPolicyVersion(policyVersion == null || policyVersion.isBlank() ? WorkflowPolicyGate.VERSION : policyVersion);
    value.setInputHash(inputHash); value.setStatus(status); value.setRejectionReason(reason); value.setDecisionJson(json(decision));
    if (status == MultiRoundWorkflowDecisionEntity.Status.ACCEPTED) value.setAcceptedAt(LocalDateTime.now());
    decisionRepository.save(value);
  }

  private String reason(Exception exception) {
    String message = exception.getMessage();
    return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(128, message.length()));
  }
  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch (JsonProcessingException exception) { throw new IllegalStateException("工作流决策无法序列化", exception); }
  }
  private String hash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte item : digest) result.append(String.format("%02x", item));
      return result.toString();
    } catch (Exception exception) { throw new IllegalStateException("无法计算工作流输入指纹", exception); }
  }
}
