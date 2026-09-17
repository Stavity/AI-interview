package interview.guide.common.async;

/** Business work runs outside a transaction; all final writes go through context.complete. */
public interface AsyncTaskHandler {
    AsyncTaskType type();
    void execute(TaskExecutionContext context);
    boolean exists(String businessId);
    default boolean isCurrent(java.util.UUID taskId, String businessId) { return exists(businessId); }
    void updateStatus(String businessId, AsyncTaskState state, String error);
}
