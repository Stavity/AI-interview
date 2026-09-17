package interview.guide.infrastructure.async;

import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.common.async.AsyncTaskState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/** Samples DB backlog without putting SQL on the Prometheus scrape path. */
@Component
public class TaskMetrics {
    private final JdbcTemplate jdbc;
    private final EnumMap<AsyncTaskState, AtomicLong> counts = new EnumMap<>(AsyncTaskState.class);
    private final AtomicLong unsent = new AtomicLong();
    private final AtomicLong oldestUnsentSeconds = new AtomicLong();
    public TaskMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (var state : AsyncTaskState.values()) {
            var count = new AtomicLong(); counts.put(state, count);
            meters.gauge("async.tasks", java.util.List.of(io.micrometer.core.instrument.Tag.of("state", state.name())), count);
        }
        meters.gauge("async.outbox.unsent", unsent);
        meters.gauge("async.outbox.oldest.seconds", oldestUnsentSeconds);
    }
    @Scheduled(fixedDelayString = "${app.tasks.metrics-interval-ms:30000}", initialDelay = 30000)
    public void sample() {
        var rows = jdbc.queryForList("select state, count(*) as total from async_task group by state");
        counts.values().forEach(count -> count.set(0));
        for (var row : rows) counts.get(AsyncTaskState.valueOf(row.get("state").toString())).set(((Number) row.get("total")).longValue());
        var backlog = jdbc.queryForMap("""
            select count(*) as total, coalesce(extract(epoch from (CURRENT_TIMESTAMP - min(available_at))), 0) as age
            from task_outbox where state <> 'SENT' and available_at <= CURRENT_TIMESTAMP
            """);
        unsent.set(((Number) backlog.get("total")).longValue());
        oldestUnsentSeconds.set(((Number) backlog.get("age")).longValue());
    }
}
