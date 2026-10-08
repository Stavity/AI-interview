package interview.guide.modules.voiceinterview.handler;

import interview.guide.common.tenant.TenantContext;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs private voice-session maintenance inside each owner's tenant context. */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewMaintenanceScheduler {
  private final UserAccountRepository userRepository;
  private final VoiceInterviewService interviewService;

  @Scheduled(fixedRate = 60_000)
  public void cleanupStaleSessions() {
    for (Long ownerUserId : userRepository.findIdsByStatus(UserAccountStatus.ACTIVE)) {
      try {
        TenantContext.runAs(ownerUserId, () -> {
          int cleaned = interviewService.cleanupStaleSessions();
          if (cleaned > 0) {
            log.info("Stale voice sessions cleaned for owner {}: {}", ownerUserId, cleaned);
          }
        });
      } catch (RuntimeException e) {
        log.error("Stale voice-session cleanup failed for owner {}", ownerUserId, e);
      }
    }
  }
}
