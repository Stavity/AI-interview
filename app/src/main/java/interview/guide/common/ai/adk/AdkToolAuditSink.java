package interview.guide.common.ai.adk;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.service.AgentRunStepAuditService;

/** Writes one safe audit event per ADK Tool invocation. */
final class AdkToolAuditSink {
  private final AgentRunStepAuditService stepAuditService;
  private final AgentRunAuditEntity run;
  private int calls;

  AdkToolAuditSink(AgentRunStepAuditService stepAuditService, AgentRunAuditEntity run) {
    this.stepAuditService = stepAuditService;
    this.run = run;
  }

  void record(String toolName, String status, long durationMs) {
    int callNo = ++calls;
    stepAuditService.record(run, callNo + 1, toolName, null, "tool_completed", durationMs,
        status, "TOOL_CALL", "tool-" + callNo);
  }
}
