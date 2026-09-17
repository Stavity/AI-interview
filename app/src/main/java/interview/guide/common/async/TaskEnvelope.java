package interview.guide.common.async;
import java.time.Instant;
import java.util.UUID;
public record TaskEnvelope(int schemaVersion, UUID eventId, UUID taskId,
                           AsyncTaskType taskType, String businessId, Instant occurredAt) {
    public void validate() {
        if (schemaVersion != 1 || eventId == null || taskId == null || taskType == null
                || businessId == null || businessId.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("Invalid task envelope");
        }
    }
}
