package interview.guide.common.ai.adk;

import com.google.adk.sessions.InMemorySessionService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Creates short-lived ADK sessions without placing interview content in session state. */
@Component
public class AdkSessionFactory {
  static final String APP_NAME = "ai-interview";
  static final String USER_ID = "local-single-user";

  private final InMemorySessionService sessionService = new InMemorySessionService();

  public SessionHandle create(long interviewId, long agentRunId, int roundNo, String contextHash,
      String policyVersion) {
    return createOrResume("mr-workflow-" + interviewId, interviewId, agentRunId, roundNo, contextHash,
        policyVersion);
  }

  /** Session ids belong to an interview workflow, not to an individual audit/run. */
  public SessionHandle createOrResume(String sessionId, long interviewId, long agentRunId, int roundNo,
      String contextHash, String policyVersion) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("interviewId", String.valueOf(interviewId));
    state.put("agentRunId", String.valueOf(agentRunId));
    state.put("roundNo", String.valueOf(roundNo));
    state.put("contextHash", contextHash);
    state.put("policyVersion", policyVersion);
    try {
      sessionService.createSession(APP_NAME, USER_ID, state, sessionId).blockingGet();
    } catch (Exception ignored) {
      // A process-local ADK session may already exist. Durable state lives in the workflow tables.
    }
    return new SessionHandle(sessionId, Map.copyOf(state));
  }

  InMemorySessionService sessionService() {
    return sessionService;
  }

  public record SessionHandle(String sessionId, Map<String, Object> state) {}
}
