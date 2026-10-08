package interview.guide.common.tenant;

import interview.guide.modules.auth.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

/** Explicit owner context for work that runs outside an authenticated HTTP request. */
public final class TenantContext {
  private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

  private TenantContext() {}

  public static Long currentTenantId() {
    return TENANT_ID.get();
  }

  /** Resolve the trusted owner established by an HTTP filter, background job, or authenticated call. */
  public static Long requireCurrentTenantId() {
    Long tenantId = TENANT_ID.get();
    if (tenantId != null) {
      return tenantId;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user
        && user.id() != null
        && user.id() > 0) {
      return user.id();
    }
    throw new IllegalStateException("An authenticated owner context is required");
  }

  public static Scope open(Long tenantId) {
    if (tenantId == null || tenantId <= 0) {
      throw new IllegalArgumentException("A positive owner user id is required");
    }
    Long previous = TENANT_ID.get();
    TENANT_ID.set(tenantId);
    return new Scope(previous);
  }

  public static void runAs(Long tenantId, Runnable action) {
    callAs(tenantId, () -> {
      action.run();
      return null;
    });
  }

  public static <T> T callAs(Long tenantId, Supplier<T> action) {
    try (Scope ignored = open(tenantId)) {
      return action.get();
    }
  }

  public static final class Scope implements AutoCloseable {
    private final Long previous;
    private boolean closed;

    private Scope(Long previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (previous == null) {
        TENANT_ID.remove();
      } else {
        TENANT_ID.set(previous);
      }
    }
  }
}
