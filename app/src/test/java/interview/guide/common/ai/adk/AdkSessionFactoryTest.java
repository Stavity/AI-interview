package interview.guide.common.ai.adk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AdkSessionFactoryTest {
  @Test
  void shouldCreateRunScopedSessionWithoutResumeContent() {
    AdkSessionFactory factory = new AdkSessionFactory();

    AdkSessionFactory.SessionHandle session = factory.create(12L, 34L, 2, "a".repeat(64),
        "interview-planning-v1");

    assertThat(session.sessionId()).isEqualTo("mr-run-12-34");
    assertThat(session.state()).containsEntry("interviewId", "12")
        .containsEntry("agentRunId", "34")
        .containsEntry("roundNo", "2")
        .containsEntry("contextHash", "a".repeat(64))
        .containsEntry("policyVersion", "interview-planning-v1");
    assertThat(session.state().toString()).doesNotContain("Candidate project");
    assertThat(factory.sessionService().getSession("ai-interview", "local-single-user", session.sessionId(),
        java.util.Optional.empty()).blockingGet().state())
        .containsEntry("interviewId", "12");
  }
}
