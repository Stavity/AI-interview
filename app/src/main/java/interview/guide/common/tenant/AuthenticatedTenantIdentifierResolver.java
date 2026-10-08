package interview.guide.common.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/** Resolves the row owner from the request-scoped tenant context, never from request data. */
@Component
public class AuthenticatedTenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {
  private static final Long NO_TENANT = 0L;

  @Override
  public Long resolveCurrentTenantIdentifier() {
    Long tenantId = TenantContext.currentTenantId();
    // Fail closed: this reserved, non-existent owner id cannot read or persist private rows.
    return tenantId == null ? NO_TENANT : tenantId;
  }

  @Override
  public boolean validateExistingCurrentSessions() {
    return true;
  }
}
