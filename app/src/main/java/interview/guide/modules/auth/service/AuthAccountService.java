package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.AuthActionTokenEntity;
import interview.guide.modules.auth.model.AuthTokenPurpose;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.model.UserPasswordCredentialEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.repository.AuthActionTokenRepository;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.repository.UserPasswordCredentialRepository;
import interview.guide.modules.auth.security.AuthEmailNormalizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthAccountService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;
    private static final String GENERIC_ACCOUNT_ACTION_MESSAGE =
            "如果该邮箱对应可用账号，邮件已发送，请查收";

    private final UserAccountRepository userRepository;
    private final UserPasswordCredentialRepository credentialRepository;
    private final AuthActionTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthEmailService emailService;
    private final AuthProperties properties;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public AuthAccountService(UserAccountRepository userRepository,
                              UserPasswordCredentialRepository credentialRepository,
                              AuthActionTokenRepository tokenRepository,
                              PasswordEncoder passwordEncoder,
                              AuthEmailService emailService,
                              AuthProperties properties,
                              FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.properties = properties;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public String register(String rawEmail, String displayName, String rawPassword) {
        emailService.ensureAvailable();
        String email = AuthEmailNormalizer.normalize(rawEmail);
        validatePasswordLength(rawPassword);
        if (userRepository.existsByEmailNormalized(email)) {
            userRepository.findByEmailNormalized(email)
                    .filter(user -> user.getStatus() == UserAccountStatus.PENDING_EMAIL_VERIFICATION)
                    .ifPresent(user -> issueToken(user, AuthTokenPurpose.VERIFY_EMAIL,
                            properties.getVerificationTokenTtl(),
                            token -> emailService.sendVerification(email, user.getDisplayName(), token)));
            return GENERIC_ACCOUNT_ACTION_MESSAGE;
        }

        UserAccountEntity draft = new UserAccountEntity();
        draft.setEmailNormalized(email);
        draft.setDisplayName(displayName.trim());
        draft.setStatus(UserAccountStatus.PENDING_EMAIL_VERIFICATION);
        draft.setRole(UserRole.USER);
        UserAccountEntity user = userRepository.saveAndFlush(draft);

        UserPasswordCredentialEntity credential = new UserPasswordCredentialEntity();
        credential.setUser(user);
        credential.setPasswordHash(passwordEncoder.encode(rawPassword));
        credential.setPasswordUpdatedAt(LocalDateTime.now());
        credentialRepository.save(credential);

        issueToken(user, AuthTokenPurpose.VERIFY_EMAIL, properties.getVerificationTokenTtl(),
                token -> emailService.sendVerification(email, user.getDisplayName(), token));
        return GENERIC_ACCOUNT_ACTION_MESSAGE;
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        LocalDateTime now = LocalDateTime.now();
        AuthActionTokenEntity token = tokenRepository.findUsableForUpdate(
                        hashToken(rawToken), AuthTokenPurpose.VERIFY_EMAIL, now)
                .orElseThrow(() -> invalidActionToken());
        UserAccountEntity user = token.getUser();
        if (user.getStatus() == UserAccountStatus.DISABLED) {
            throw invalidActionToken();
        }
        token.setConsumedAt(now);
        user.setStatus(UserAccountStatus.ACTIVE);
    }

    @Transactional
    public String resendVerification(String rawEmail) {
        emailService.ensureAvailable();
        String email = AuthEmailNormalizer.normalize(rawEmail);
        userRepository.findByEmailNormalized(email)
                .filter(user -> user.getStatus() == UserAccountStatus.PENDING_EMAIL_VERIFICATION)
                .ifPresent(user -> issueToken(user, AuthTokenPurpose.VERIFY_EMAIL,
                        properties.getVerificationTokenTtl(),
                        token -> emailService.sendVerification(email, user.getDisplayName(), token)));
        return GENERIC_ACCOUNT_ACTION_MESSAGE;
    }

    @Transactional
    public String requestPasswordReset(String rawEmail) {
        emailService.ensureAvailable();
        String email = AuthEmailNormalizer.normalize(rawEmail);
        userRepository.findByEmailNormalized(email)
                .filter(user -> user.getStatus() == UserAccountStatus.ACTIVE)
                .ifPresent(user -> issueToken(user, AuthTokenPurpose.RESET_PASSWORD,
                        properties.getPasswordResetTokenTtl(),
                        token -> emailService.sendPasswordReset(email, user.getDisplayName(), token)));
        return GENERIC_ACCOUNT_ACTION_MESSAGE;
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        validatePasswordLength(newPassword);
        LocalDateTime now = LocalDateTime.now();
        AuthActionTokenEntity token = tokenRepository.findUsableForUpdate(
                        hashToken(rawToken), AuthTokenPurpose.RESET_PASSWORD, now)
                .orElseThrow(() -> invalidActionToken());
        UserAccountEntity user = token.getUser();
        if (user.getStatus() != UserAccountStatus.ACTIVE) {
            throw invalidActionToken();
        }

        UserPasswordCredentialEntity credential = credentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> invalidActionToken());
        credential.setPasswordHash(passwordEncoder.encode(newPassword));
        credential.setPasswordUpdatedAt(now);
        token.setConsumedAt(now);
        sessionRepository.findByPrincipalName(user.getEmailNormalized())
                .keySet()
                .forEach(sessionRepository::deleteById);
    }

    private void issueToken(UserAccountEntity user,
                            AuthTokenPurpose purpose,
                            java.time.Duration ttl,
                            java.util.function.Consumer<String> deliver) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.consumeAllActiveForUser(user.getId(), purpose, now);
        byte[] secret = new byte[32];
        SECURE_RANDOM.nextBytes(secret);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        AuthActionTokenEntity token = new AuthActionTokenEntity();
        token.setUser(user);
        token.setTokenHash(hashToken(rawToken));
        token.setPurpose(purpose);
        token.setExpiresAt(now.plus(ttl));
        tokenRepository.save(token);

        // Send before the transaction commits so an SMTP failure rolls back the new account/token.
        deliver.accept(rawToken);
    }

    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码过长，请控制在 72 个 UTF-8 字节以内");
        }
    }

    private static BusinessException invalidActionToken() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "链接无效、已使用或已过期，请重新申请");
    }
}
