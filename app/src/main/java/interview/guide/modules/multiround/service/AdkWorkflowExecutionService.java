package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Durable, bounded execution envelope around every workflow-level ADK call. */
@Service
@RequiredArgsConstructor
public class AdkWorkflowExecutionService {
  private final AgentRunAuditService auditService;
  private final AgentRunStepAuditService stepAuditService;

  public <T> T execute(MultiRoundInterviewEntity interview, String trigger, String inputFingerprint,
      Supplier<T> action) {
    AgentRunAuditEntity run = auditService.start(interview, trigger, inputFingerprint);
    long startedAt = System.nanoTime();
    stepAuditService.record(run, 1, "EVIDENCE_AGENT", "bounded-context", "dispatched", 0, "DISPATCHED");
    stepAuditService.record(run, 2, "STRATEGY_AGENT", "evidence-state", "dispatched", 0, "DISPATCHED");
    RuntimeException last = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        T result = action.get();
        stepAuditService.record(run, 2 + attempt, "ROOT_AGENT", "attempt=" + attempt, "structured-output",
            elapsed(startedAt), "SUCCEEDED");
        auditService.succeeded(run, "runtime=GOOGLE_ADK;attempt=" + attempt);
        return result;
      } catch (RuntimeException error) {
        last = error;
        stepAuditService.record(run, 2 + attempt, "ROOT_AGENT", "attempt=" + attempt, "retry-or-fallback",
            elapsed(startedAt), attempt == 1 ? "RETRYING" : "FAILED");
      }
    }
    auditService.failed(run, hasTimeoutCause(last) ? "ADK_TIMEOUT" : "ADK_RETRY_EXHAUSTED");
    throw last == null ? new IllegalStateException("ADK 未返回结果") : last;
  }

  private long elapsed(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
  private boolean hasTimeoutCause(Throwable error) {
    for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
      if (cursor instanceof java.util.concurrent.TimeoutException) return true;
    }
    return false;
  }
}
