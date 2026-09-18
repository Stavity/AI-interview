package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.repository.AgentRunAuditRepository;
import interview.guide.modules.multiround.repository.AgentRunStepAuditRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    var runs = runRepository.findByInterviewIdOrderByStartedAtDesc(interviewId);
    if (runs.isEmpty()) {
      return List.of();
    }
    // 批量取步骤后在内存分组：原实现按 run 逐条查询，50 个 run 即 51 次查询
    Map<Long, List<StepTrace>> stepsByRun = stepRepository
        .findByRunIdInOrderByStepNoAsc(runs.stream().map(AgentRunAuditEntity::getId).toList())
        .stream()
        .collect(Collectors.groupingBy(
            step -> step.getRun().getId(),
            Collectors.mapping(step -> new StepTrace(step.getStepNo(), step.getToolName(), step.getStatus(),
                step.getOutputSummary(), step.getDurationMs(), step.getCreatedAt()), Collectors.toList())));

    return runs.stream()
        .map(run -> new RunTrace(run.getId(), run.getTriggerType(), run.getStatus().name(), run.getErrorCode(),
            run.getStartedAt(), run.getFinishedAt(),
            stepsByRun.getOrDefault(run.getId(), List.of())))
        .toList();
  }

  public record RunTrace(Long id, String triggerType, String status, String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt, List<StepTrace> steps) {}
  public record StepTrace(int stepNo, String toolName, String status, String outputSummary, Long durationMs, LocalDateTime createdAt) {}
}
