package interview.guide.common.tenant;

import interview.guide.modules.auth.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Establishes the trusted database owner after Spring Security has loaded the session. */
@Component
public class AuthenticatedTenantContextFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
        || user.id() == null
        || user.id() <= 0) {
      filterChain.doFilter(request, response);
      return;
    }

    try (TenantContext.Scope ignored = TenantContext.open(user.id())) {
      filterChain.doFilter(request, response);
    }
  }
}
