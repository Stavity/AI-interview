package interview.guide.common.ai.adk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.multiround.agent")
public class AgentRuntimeProperties {
  public enum Runtime { LEGACY, GOOGLE_ADK }

  private Runtime runtime = Runtime.GOOGLE_ADK;
  private String policyVersion = "workflow-v1";
  private int maxToolCalls = 5;
  private long timeoutMs = 60_000;
}
