package interview.guide.modules.questiontag.service;

import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Coordinates bounded, independently committed backfill batches across legacy data sources. */
@Service
public class QuestionTagBackfillService {

  private static final int MAX_BATCH_SIZE = 500;
  private static final int MAX_RECORDS_PER_RUN = 100_000;

  private final QuestionTagBackfillBatchService batchService;
  private final QuestionTagQueryService queryService;
  private final MeterRegistry meterRegistry;

  public QuestionTagBackfillService(
      QuestionTagBackfillBatchService batchService,
      QuestionTagQueryService queryService,
      @Autowired(required = false) MeterRegistry meterRegistry
  ) {
    this.batchService = batchService;
    this.queryService = queryService;
    this.meterRegistry = meterRegistry;
  }

  public RunResult run(
      boolean dryRun,
      int requestedBatchSize,
      int requestedMaxRecords,
      long afterKnowledgeBaseId,
      long afterSessionId
  ) {
    int taxonomyVersion = queryService.currentVersionNumber();
    int batchSize = Math.max(1, Math.min(MAX_BATCH_SIZE, requestedBatchSize));
    int maxRecords = requestedMaxRecords <= 0
        ? Integer.MAX_VALUE
        : Math.min(MAX_RECORDS_PER_RUN, requestedMaxRecords);
    Counters counters = new Counters();
    Long knowledgeBaseCursor = Math.max(0L, afterKnowledgeBaseId);
    Long sessionCursor = Math.max(0L, afterSessionId);

    while (counters.scanned() < maxRecords) {
      int limit = Math.min(batchSize, maxRecords - counters.scanned());
      QuestionTagBackfillBatchService.BatchResult batch = batchService.backfillKnowledgeBaseBatch(
          taxonomyVersion, knowledgeBaseCursor, limit, dryRun);
      counters.add(batch);
      if (batch.scanned() == 0 || batch.cursor() == null || batch.cursor() <= knowledgeBaseCursor) {
        break;
      }
      knowledgeBaseCursor = batch.cursor();
      if (batch.scanned() < limit) {
        break;
      }
    }

    while (counters.scanned() < maxRecords) {
      int limit = Math.min(batchSize, maxRecords - counters.scanned());
      QuestionTagBackfillBatchService.BatchResult batch = batchService.backfillInterviewSessionBatch(
          taxonomyVersion, sessionCursor, limit, dryRun);
      counters.add(batch);
      if (batch.scanned() == 0 || batch.cursor() == null || batch.cursor() <= sessionCursor) {
        break;
      }
      sessionCursor = batch.cursor();
      if (batch.scanned() < limit) {
        break;
      }
    }

    RunResult result = new RunResult(taxonomyVersion, dryRun, batchSize,
        maxRecords == Integer.MAX_VALUE ? 0 : maxRecords,
        counters.scanned(), counters.autoTagged, counters.reviewRequired, counters.unmatched,
        counters.skipped, counters.parseErrors, knowledgeBaseCursor, sessionCursor,
        counters.scanned() >= maxRecords);
    recordMetrics(result);
    return result;
  }

  private void recordMetrics(RunResult result) {
    if (meterRegistry == null) {
      return;
    }
    String mode = result.dryRun() ? "dry_run" : "apply";
    recordCount("auto_tagged", result.autoTagged(), mode);
    recordCount("review_required", result.reviewRequired(), mode);
    recordCount("unmatched", result.unmatched(), mode);
    recordCount("skipped", result.skipped(), mode);
    recordCount("parse_error", result.parseErrors(), mode);
    int classified = result.autoTagged() + result.reviewRequired();
    if (classified > 0) {
      DistributionSummary.builder("app.question_tag_backfill.tag_coverage")
          .tag("mode", mode).register(meterRegistry)
          .record((double) (classified - result.unmatched()) / classified);
      DistributionSummary.builder("app.question_tag_backfill.no_result_rate")
          .tag("mode", mode).register(meterRegistry)
          .record((double) result.unmatched() / classified);
    }
  }

  private void recordCount(String outcome, int count, String mode) {
    if (count > 0) {
      meterRegistry.counter("app.question_tag_backfill.records", "outcome", outcome, "mode", mode)
          .increment(count);
    }
  }

  private static final class Counters {
    private int scanned;
    private int autoTagged;
    private int reviewRequired;
    private int unmatched;
    private int skipped;
    private int parseErrors;

    private void add(QuestionTagBackfillBatchService.BatchResult result) {
      scanned += result.scanned();
      autoTagged += result.autoTagged();
      reviewRequired += result.reviewRequired();
      unmatched += result.unmatched();
      skipped += result.skipped();
      parseErrors += result.parseErrors();
    }

    private int scanned() {
      return scanned;
    }
  }

  public record RunResult(
      int taxonomyVersion,
      boolean dryRun,
      int batchSize,
      int maxRecords,
      int scanned,
      int autoTagged,
      int reviewRequired,
      int unmatched,
      int skipped,
      int parseErrors,
      Long lastKnowledgeBaseId,
      Long lastSessionId,
      boolean reachedRecordLimit
  ) {}
}
