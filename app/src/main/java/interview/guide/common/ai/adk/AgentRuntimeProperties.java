package interview.guide.common.ai.adk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.multiround.agent")
public class AgentRuntimeProperties {
  public enum Runtime { LEGACY, SHADOW, GOOGLE_ADK }

  private Runtime runtime = Runtime.LEGACY;
  private String policyVersion = "interview-planning-v1";
  private int maxToolCalls = 4;
  private long timeoutMs = 60_000;
}
