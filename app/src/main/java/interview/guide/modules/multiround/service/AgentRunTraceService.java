package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.repository.AgentRunAuditRepository;
import interview.guide.modules.multiround.repository.AgentRunStepAuditRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentRunTraceService {
  private final AgentRunAuditRepository runRepository;
  private final AgentRunStepAuditRepository stepRepository;

  @Transactional(readOnly = true)
  public List<RunTrace> list(Long interviewId) {
    return runRepository.findByInterviewIdOrderByStartedAtDesc(interviewId).stream()
        .map(run -> new RunTrace(run.getId(), run.getTriggerType(), run.getStatus().name(), run.getErrorCode(), run.getStartedAt(), run.getFinishedAt(),
            stepRepository.findByRunIdOrderByStepNoAsc(run.getId()).stream()
                .map(step -> new StepTrace(step.getStepNo(), step.getToolName(), step.getStatus(), step.getOutputSummary(), step.getDurationMs(), step.getCreatedAt()))
                .toList()))
        .toList();
  }

  public record RunTrace(Long id, String triggerType, String status, String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt, List<StepTrace> steps) {}
  public record StepTrace(int stepNo, String toolName, String status, String outputSummary, Long durationMs, LocalDateTime createdAt) {}
}
