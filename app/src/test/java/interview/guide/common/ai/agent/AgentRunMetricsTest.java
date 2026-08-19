package interview.guide.common.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AgentRunMetricsTest {
  @Test
  void shouldRecordRunFallbackAndContextMetricsWithBoundedTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AgentRunMetrics metrics = new AgentRunMetrics(registry);

    metrics.recordContext("resume", 480);
    metrics.recordRun("GOOGLE_ADK", "SUCCEEDED", "ROUND_START", "provider-a", 250);
    metrics.recordFallback("ADK_TIMEOUT", "TECHNICAL_1");

    assertThat(registry.find("interview_agent_context_chars").tag("section", "resume").summary()
        .count()).isEqualTo(1);
    assertThat(registry.find("interview_agent_run_total").tags("runtime", "GOOGLE_ADK", "status",
        "SUCCEEDED", "trigger", "ROUND_START", "provider", "provider-a").counter().count())
        .isEqualTo(1);
    assertThat(registry.find("interview_agent_duration_seconds").tags("runtime", "GOOGLE_ADK",
        "outcome", "SUCCEEDED").timer().count()).isEqualTo(1);
    assertThat(registry.find("interview_agent_fallback_total").tags("reason", "ADK_TIMEOUT",
        "round_type", "TECHNICAL_1").counter().count()).isEqualTo(1);
  }
}
