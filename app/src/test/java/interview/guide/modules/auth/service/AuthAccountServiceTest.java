package interview.guide.modules.auth.service;

import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.AuthActionTokenEntity;
import interview.guide.modules.auth.model.AuthTokenPurpose;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.model.UserPasswordCredentialEntity;
import interview.guide.modules.auth.repository.AuthActionTokenRepository;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.repository.UserPasswordCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthAccountServiceTest {
    private UserAccountRepository users;
    private UserPasswordCredentialRepository credentials;
    private AuthActionTokenRepository tokens;
    private AuthEmailService email;
    private BCryptPasswordEncoder passwordEncoder;
    private FindByIndexNameSessionRepository<Session> sessions;
    private AuthAccountService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        credentials = mock(UserPasswordCredentialRepository.class);
        tokens = mock(AuthActionTokenRepository.class);
        email = mock(AuthEmailService.class);
        passwordEncoder = new BCryptPasswordEncoder(4);
        AuthProperties properties = new AuthProperties();
        sessions =
                (FindByIndexNameSessionRepository<Session>) mock(FindByIndexNameSessionRepository.class);
        service = new AuthAccountService(users, credentials, tokens, passwordEncoder, email, properties, sessions);
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndStoresOnlyTokenHash() {
        AtomicReference<String> deliveredToken = new AtomicReference<>();
        when(users.existsByEmailNormalized("learner@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity user = invocation.getArgument(0);
            user.setId(41L);
            return user;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            deliveredToken.set(invocation.getArgument(2));
            return null;
        }).when(email).sendVerification(eq("learner@example.com"), eq("Learner"), anyString());

        service.register(" Learner@Example.com ", " Learner ", "a-safe-password-123");

        var credentialCaptor = org.mockito.ArgumentCaptor.forClass(UserPasswordCredentialEntity.class);
        verify(credentials).save(credentialCaptor.capture());
        UserPasswordCredentialEntity credential = credentialCaptor.getValue();
        assertTrue(passwordEncoder.matches("a-safe-password-123", credential.getPasswordHash()));
        assertNotEquals("a-safe-password-123", credential.getPasswordHash());

        var tokenCaptor = org.mockito.ArgumentCaptor.forClass(AuthActionTokenEntity.class);
        verify(tokens).save(tokenCaptor.capture());
        AuthActionTokenEntity storedToken = tokenCaptor.getValue();
        assertEquals(AuthTokenPurpose.VERIFY_EMAIL, storedToken.getPurpose());
        assertEquals(64, storedToken.getTokenHash().length());
        assertNotEquals(deliveredToken.get(), storedToken.getTokenHash());
        assertEquals(43, deliveredToken.get().length());
        verify(users).existsByEmailNormalized("learner@example.com");
    }

    @Test
    void duplicateRegistrationDoesNotSendAnEmail() {
        when(users.existsByEmailNormalized("learner@example.com")).thenReturn(true);

        service.register("LEARNER@example.com", "Learner", "a-safe-password-123");

        verify(email, never()).sendVerification(anyString(), anyString(), anyString());
        verify(credentials, never()).save(any());
    }

    @Test
    void emailVerificationActivatesPendingUserAndConsumesToken() {
        UserAccountEntity user = new UserAccountEntity();
        user.setStatus(UserAccountStatus.PENDING_EMAIL_VERIFICATION);
        AuthActionTokenEntity token = new AuthActionTokenEntity();
        token.setUser(user);
        token.setPurpose(AuthTokenPurpose.VERIFY_EMAIL);
        when(tokens.findUsableForUpdate(anyString(), eq(AuthTokenPurpose.VERIFY_EMAIL), any(LocalDateTime.class)))
                .thenReturn(Optional.of(token));

        service.verifyEmail("email-action-token");

        assertEquals(UserAccountStatus.ACTIVE, user.getStatus());
        assertTrue(token.getConsumedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
    }

    @Test
    void passwordResetChangesHashConsumesTokenAndRevokesExistingSessions() {
        UserAccountEntity user = new UserAccountEntity();
        user.setId(41L);
        user.setEmailNormalized("learner@example.com");
        user.setStatus(UserAccountStatus.ACTIVE);
        UserPasswordCredentialEntity credential = new UserPasswordCredentialEntity();
        credential.setPasswordHash(passwordEncoder.encode("old-safe-password-123"));
        AuthActionTokenEntity token = new AuthActionTokenEntity();
        token.setUser(user);
        token.setPurpose(AuthTokenPurpose.RESET_PASSWORD);
        when(tokens.findUsableForUpdate(anyString(), eq(AuthTokenPurpose.RESET_PASSWORD), any(LocalDateTime.class)))
                .thenReturn(Optional.of(token));
        when(credentials.findByUserId(41L)).thenReturn(Optional.of(credential));
        when(sessions.findByPrincipalName("learner@example.com"))
                .thenReturn(Map.of("session-id", mock(Session.class)));

        service.resetPassword("password-reset-token", "new-safe-password-456");

        assertTrue(passwordEncoder.matches("new-safe-password-456", credential.getPasswordHash()));
        assertNotEquals("old-safe-password-123", credential.getPasswordHash());
        assertTrue(token.getConsumedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
        verify(sessions).deleteById("session-id");
    }

    @Test
    void expiredOrReplayedActionTokenIsRejected() {
        when(tokens.findUsableForUpdate(anyString(), eq(AuthTokenPurpose.VERIFY_EMAIL), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                interview.guide.common.exception.BusinessException.class,
                () -> service.verifyEmail("expired-or-used-token"));
    }
}
