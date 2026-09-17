package interview.guide.common.async;

import interview.guide.common.exception.BusinessException;
import org.springframework.web.client.RestClientResponseException;
import java.util.Set;

/** Only deterministic input/auth failures are terminal; provider outages use the bounded retry budget. */
public final class TaskFailurePolicy {
    private static final Set<Integer> TERMINAL_CODES = Set.of(400, 401, 403, 404, 2001, 3001, 6001, 10001, 11001);
    private TaskFailurePolicy() {}
    public static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) return false;
            if (cause instanceof BusinessException business && TERMINAL_CODES.contains(business.getCode())) return false;
            if (cause instanceof RestClientResponseException http) {
                int code = http.getStatusCode().value();
                return code == 408 || code == 429 || code >= 500;
            }
            if (cause == cause.getCause()) break;
        }
        return true;
    }
}
