package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.AgentRunAuditRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AgentRunAuditService {
  private final AgentRunAuditRepository repository;
  @Transactional
  public AgentRunAuditEntity start(MultiRoundInterviewEntity interview, String triggerType, String input) {
    var run = new AgentRunAuditEntity(); run.setInterview(interview); run.setTriggerType(triggerType); run.setInputHash(sha256(input)); run.setStartedAt(LocalDateTime.now()); return repository.save(run);
  }
  @Transactional
  public void succeeded(AgentRunAuditEntity run, String outputSummary) { run.setStatus(AgentRunAuditEntity.Status.SUCCEEDED); run.setOutputJson(outputSummary); run.setFinishedAt(LocalDateTime.now()); repository.save(run); }
  @Transactional
  public void failed(AgentRunAuditEntity run, String errorCode) { run.setStatus(AgentRunAuditEntity.Status.FAILED); run.setErrorCode(errorCode); run.setFinishedAt(LocalDateTime.now()); repository.save(run); }
  private String sha256(String text) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder value = new StringBuilder(64); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString(); } catch (Exception error) { throw new IllegalStateException(error); } }
}
