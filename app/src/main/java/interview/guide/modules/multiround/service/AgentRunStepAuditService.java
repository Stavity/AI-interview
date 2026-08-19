package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.AgentRunStepAuditEntity;
import interview.guide.modules.multiround.repository.AgentRunStepAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentRunStepAuditService {
  private static final int SUMMARY_MAX_LENGTH = 500;
  private final AgentRunStepAuditRepository repository;

  @Transactional
  public AgentRunStepAuditEntity record(
      AgentRunAuditEntity run,
      int stepNo,
      String toolName,
      String inputSummary,
      String outputSummary,
      long durationMs,
      String status) {
    return record(run, stepNo, toolName, inputSummary, outputSummary, durationMs, status, "MODEL", null);
  }

  @Transactional
  public AgentRunStepAuditEntity record(
      AgentRunAuditEntity run,
      int stepNo,
      String toolName,
      String inputSummary,
      String outputSummary,
      long durationMs,
      String status,
      String eventType,
      String toolCallId) {
    var step = new AgentRunStepAuditEntity();
    step.setRun(run);
    step.setStepNo(stepNo);
    step.setToolName(toolName);
    step.setInputSummary(bound(inputSummary));
    step.setOutputSummary(bound(outputSummary));
    step.setDurationMs(durationMs);
    step.setStatus(status);
    step.setEventType(bound(eventType));
    step.setToolCallId(bound(toolCallId));
    return repository.save(step);
  }

  private String bound(String value) {
    if (value == null || value.isBlank()) return null;
    return value.length() <= SUMMARY_MAX_LENGTH ? value : value.substring(0, SUMMARY_MAX_LENGTH);
  }
}
