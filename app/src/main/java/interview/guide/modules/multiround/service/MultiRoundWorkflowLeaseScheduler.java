package interview.guide.modules.multiround.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class MultiRoundWorkflowLeaseScheduler {
  private final MultiRoundWorkflowService workflowService;

  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  void pauseExpiredWorkflows() {
    int paused = workflowService.pauseExpiredWorkflows();
    if (paused > 0) log.info("已暂停 {} 个超时的多轮面试工作流", paused);
  }
}
