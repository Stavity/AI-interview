package interview.guide.common.async;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
@Component
public class AsyncTaskHandlerRegistry {
    private final Map<AsyncTaskType, AsyncTaskHandler> handlers = new EnumMap<>(AsyncTaskType.class);
    public AsyncTaskHandlerRegistry(List<AsyncTaskHandler> values) {
        for (var handler : values) {
            if (handlers.put(handler.type(), handler) != null)
                throw new IllegalStateException("Duplicate task handler: " + handler.type());
        }
    }
    public AsyncTaskHandler get(AsyncTaskType type) {
        var handler = handlers.get(type);
        if (handler == null) throw new IllegalStateException("Missing task handler: " + type);
        return handler;
    }
}
