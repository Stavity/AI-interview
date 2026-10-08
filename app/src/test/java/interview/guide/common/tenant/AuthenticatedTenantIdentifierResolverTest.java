package interview.guide.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthenticatedTenantIdentifierResolverTest {
  private final AuthenticatedTenantIdentifierResolver resolver =
      new AuthenticatedTenantIdentifierResolver();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsNoTenantWithoutResolvingDeferredSecurityContext() {
    SecurityContextHolder.setDeferredContext(() -> {
      throw new AssertionError("Hibernate tenant resolution must not load the security context");
    });

    assertEquals(0L, resolver.resolveCurrentTenantIdentifier());
  }

  @Test
  void returnsExplicitTenantAndRestoresNestedScope() {
    try (TenantContext.Scope ignored = TenantContext.open(41L)) {
      assertEquals(41L, resolver.resolveCurrentTenantIdentifier());
      try (TenantContext.Scope nested = TenantContext.open(42L)) {
        assertEquals(42L, resolver.resolveCurrentTenantIdentifier());
      }
      assertEquals(41L, resolver.resolveCurrentTenantIdentifier());
    }

    assertEquals(0L, resolver.resolveCurrentTenantIdentifier());
  }
}
