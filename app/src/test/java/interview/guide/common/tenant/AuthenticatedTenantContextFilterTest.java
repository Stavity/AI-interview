package interview.guide.common.tenant;

import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.security.AuthenticatedUser;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatedTenantContextFilterTest {
  private final AuthenticatedTenantContextFilter filter = new AuthenticatedTenantContextFilter();

  @AfterEach
  void clearContexts() {
    SecurityContextHolder.clearContext();
    assertNull(TenantContext.currentTenantId());
  }

  @Test
  void exposesAuthenticatedOwnerOnlyWhileRequestIsRunning() throws ServletException, IOException {
    AuthenticatedUser user = new AuthenticatedUser(
        7L, UUID.randomUUID(), "owner@example.com", "Owner", UserRole.USER);
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));

    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
        (request, response) -> assertEquals(7L, TenantContext.requireCurrentTenantId()));

    assertNull(TenantContext.currentTenantId());
  }

  @Test
  void clearsOwnerWhenDownstreamFails() {
    AuthenticatedUser user = new AuthenticatedUser(
        8L, UUID.randomUUID(), "owner@example.com", "Owner", UserRole.USER);
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));

    assertThrows(ServletException.class, () -> filter.doFilter(
        new MockHttpServletRequest(), new MockHttpServletResponse(),
        (request, response) -> {
          assertEquals(8L, TenantContext.requireCurrentTenantId());
          throw new ServletException("downstream failure");
        }));
    assertNull(TenantContext.currentTenantId());
  }
}
