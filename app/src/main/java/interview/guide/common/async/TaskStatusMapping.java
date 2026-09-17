package interview.guide.common.async;
import interview.guide.common.model.AsyncTaskStatus;
public final class TaskStatusMapping {
    private TaskStatusMapping() {}
    public static AsyncTaskStatus legacy(AsyncTaskState state) {
        return switch (state) {
            case RUNNING -> AsyncTaskStatus.PROCESSING;
            case SUCCEEDED -> AsyncTaskStatus.COMPLETED;
            case FAILED, CANCELLED -> AsyncTaskStatus.FAILED;
            default -> AsyncTaskStatus.PENDING;
        };
    }
}
