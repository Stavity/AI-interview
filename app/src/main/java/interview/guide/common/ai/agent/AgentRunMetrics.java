package interview.guide.common.ai.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Records bounded, non-sensitive operational measurements for interview planning. */
@Component
public class AgentRunMetrics {
  private final MeterRegistry registry;

  public AgentRunMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordRun(String runtime, String status, String trigger, String provider, long durationMs) {
    String safeRuntime = tag(runtime, "UNKNOWN");
    String safeStatus = tag(status, "UNKNOWN");
    Counter.builder("interview_agent_run_total")
        .tags("runtime", safeRuntime, "status", safeStatus, "trigger", tag(trigger, "UNKNOWN"),
            "provider", tag(provider, "default"))
        .register(registry)
        .increment();
    Timer.builder("interview_agent_duration_seconds")
        .tags("runtime", safeRuntime, "outcome", safeStatus)
        .register(registry)
        .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
  }

  public void recordToolCall(String tool, String status) {
    Counter.builder("interview_agent_tool_call_total")
        .tags("tool", tag(tool, "unknown"), "status", tag(status, "UNKNOWN"))
        .register(registry)
        .increment();
  }

  public void recordFallback(String reason, String roundType) {
    Counter.builder("interview_agent_fallback_total")
        .tags("reason", tag(reason, "UNKNOWN"), "round_type", tag(roundType, "UNKNOWN"))
        .register(registry)
        .increment();
  }

  public void recordPolicyRejection(String reason) {
    Counter.builder("interview_agent_policy_rejection_total")
        .tag("reason", tag(reason, "UNKNOWN"))
        .register(registry)
        .increment();
  }

  public void recordContext(String section, int chars) {
    DistributionSummary.builder("interview_agent_context_chars")
        .tag("section", tag(section, "unknown"))
        .register(registry)
        .record(Math.max(0, chars));
  }

  public void recordShadowComparison(boolean categoryMatch) {
    Counter.builder("interview_agent_shadow_category_match_total")
        .tag("matched", String.valueOf(categoryMatch))
        .register(registry)
        .increment();
  }

  private String tag(String value, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    return value.length() <= 64 ? value : value.substring(0, 64);
  }
}
