package interview.guide.common.async;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public record TaskExecutionContext(UUID taskId, Long ownerUserId, String businessId, long executionVersion,
                                   Map<String, String> input, Consumer<Runnable> completion) {
    public void complete(Runnable writeResult) { completion.accept(writeResult); }
    public String required(String name) {
        String value = input.get(name);
        if (value == null) throw new IllegalArgumentException("Missing task input: " + name);
        return value;
    }
}
