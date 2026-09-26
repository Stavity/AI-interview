package interview.guide.modules.questiontag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Opt-in operational entry point; disabled and dry-run by default. */
@Component
@ConditionalOnProperty(name = "app.question-tags.backfill.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class QuestionTagBackfillStartupRunner {

  private final QuestionTagBackfillService backfillService;

  @Value("${app.question-tags.backfill.dry-run:true}")
  private boolean dryRun;

  @Value("${app.question-tags.backfill.batch-size:100}")
  private int batchSize;

  /** Zero means process all eligible rows, in bounded database transactions. */
  @Value("${app.question-tags.backfill.max-records:0}")
  private int maxRecords;

  @Value("${app.question-tags.backfill.after-knowledge-base-id:0}")
  private long afterKnowledgeBaseId;

  @Value("${app.question-tags.backfill.after-session-id:0}")
  private long afterSessionId;

  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public void runBackfill() {
    try {
      QuestionTagBackfillService.RunResult result = backfillService.run(dryRun, batchSize,
          maxRecords, afterKnowledgeBaseId, afterSessionId);
      log.info("题目标签历史回填结束: version={}, dryRun={}, scanned={}, autoTagged={}, "
              + "reviewRequired={}, unmatched={}, skipped={}, parseErrors={}, kbCursor={}, "
              + "sessionCursor={}, reachedLimit={}",
          result.taxonomyVersion(), result.dryRun(), result.scanned(), result.autoTagged(),
          result.reviewRequired(), result.unmatched(), result.skipped(), result.parseErrors(),
          result.lastKnowledgeBaseId(), result.lastSessionId(), result.reachedRecordLimit());
    } catch (RuntimeException e) {
      log.error("题目标签历史回填失败；已经完成的批次保持提交，失败批次已回滚", e);
    }
  }
}
