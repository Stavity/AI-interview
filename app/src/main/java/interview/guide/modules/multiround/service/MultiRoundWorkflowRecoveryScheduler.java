package interview.guide.modules.multiround.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Slf4j @RequiredArgsConstructor
public class MultiRoundWorkflowRecoveryScheduler {
  private final MultiRoundWorkflowService workflowService;
  @Scheduled(fixedDelayString = "${app.multiround.workflow.recovery-interval-ms:60000}")
  public void pauseExpiredWorkflows() {
    int paused = workflowService.pauseExpiredLeases();
    if (paused > 0) log.info("已暂停 {} 个超时多轮工作流", paused);
  }
}
