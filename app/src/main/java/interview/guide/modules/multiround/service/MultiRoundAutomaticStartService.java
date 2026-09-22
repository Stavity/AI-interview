package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Starts the first executable node after bootstrap without making a failed question draft fatal to creation. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundAutomaticStartService {
  private final MultiRoundRepository roundRepository;
  private final MultiRoundOperationService operationService;

  public void ensureFirstNodeStarted(MultiRoundInterviewEntity interview) {
    ensureNextPendingNodeStarted(interview);
  }

  public void ensureNextPendingNodeStarted(MultiRoundInterviewEntity interview) {
    if (interview.getStatus() != MultiRoundInterviewEntity.Status.ROUND_READY) return;
    roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId()).stream()
        .filter(round -> round.getRoundNo() == interview.getCurrentRoundNo() + 1)
        .filter(round -> round.getStatus() == MultiRoundEntity.Status.PENDING)
        .findFirst()
        .ifPresent(round -> start(interview, round));
  }

  private void start(MultiRoundInterviewEntity interview, MultiRoundEntity round) {
    try {
      operationService.startRound(round.getId(), "system-initial-start:" + interview.getId() + ":" + round.getId());
    } catch (RuntimeException error) {
      // Bootstrap must remain recoverable. The node is still PENDING/ROUND_READY and a later
      // recovery job can invoke the same stable idempotency key.
      log.warn("首个多轮节点自动准备失败: interviewId={}, roundId={}, error={}",
          interview.getId(), round.getId(), error.getMessage());
    }
  }
}
