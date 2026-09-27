package interview.guide.modules.auth.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.AuthDtos;
import interview.guide.modules.auth.security.AuthenticatedUser;
import interview.guide.modules.auth.service.AuthAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String GENERIC_ACTION_MESSAGE =
            "如果该邮箱对应可用账号，邮件已发送，请查收";

    private final AuthAccountService accountService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthController(AuthAccountService accountService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy,
                          CsrfTokenRepository csrfTokenRepository) {
        this.accountService = accountService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/csrf")
    public Result<AuthDtos.CsrfTokenResponse> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return Result.error(ErrorCode.INTERNAL_ERROR, "无法初始化安全令牌，请刷新页面后重试");
        }
        return Result.success(new AuthDtos.CsrfTokenResponse(token.getToken()));
    }

    @PostMapping("/register")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, timeUnit = RateLimit.TimeUnit.HOURS)
    public ResponseEntity<Result<Void>> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        try {
            String message = accountService.register(request.email(), request.displayName(), request.password());
            return ResponseEntity.ok(Result.success(message, null));
        } catch (DataIntegrityViolationException duplicateOrInvalidAccount) {
            // A concurrent duplicate registration must not reveal whether the address exists.
            return ResponseEntity.ok(Result.success(GENERIC_ACTION_MESSAGE, null));
        }
    }

    @PostMapping("/login")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseEntity<Result<AuthDtos.CurrentUserResponse>> login(
            @Valid @RequestBody AuthDtos.LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (AuthenticationException rejected) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(ErrorCode.UNAUTHORIZED, "邮箱或密码不正确，或邮箱尚未验证"));
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        csrfTokenRepository.saveToken(null, servletRequest, servletResponse);

        return ResponseEntity.ok(Result.success(toCurrentUser(authentication)));
    }

    @GetMapping("/me")
    public Result<AuthDtos.CurrentUserResponse> currentUser(Authentication authentication) {
        return Result.success(toCurrentUser(authentication));
    }

    @PostMapping("/email/verify")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<Void> verifyEmail(@Valid @RequestBody AuthDtos.TokenActionRequest request) {
        accountService.verifyEmail(request.token());
        return Result.success("邮箱验证成功，请登录", null);
    }

    @PostMapping("/email/resend")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3, timeUnit = RateLimit.TimeUnit.HOURS)
    public Result<Void> resendVerification(@Valid @RequestBody AuthDtos.EmailActionRequest request) {
        return Result.success(accountService.resendVerification(request.email()), null);
    }

    @PostMapping("/password/forgot")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3, timeUnit = RateLimit.TimeUnit.HOURS)
    public Result<Void> forgotPassword(@Valid @RequestBody AuthDtos.EmailActionRequest request) {
        return Result.success(accountService.requestPasswordReset(request.email()), null);
    }

    @PostMapping("/password/reset")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, timeUnit = RateLimit.TimeUnit.HOURS)
    public Result<Void> resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        accountService.resetPassword(request.token(), request.newPassword());
        return Result.success("密码已更新，请使用新密码登录", null);
    }

    private static AuthDtos.CurrentUserResponse toCurrentUser(Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return new AuthDtos.CurrentUserResponse(
                user.publicId().toString(), user.email(), user.displayName(), user.role().name());
    }
}
