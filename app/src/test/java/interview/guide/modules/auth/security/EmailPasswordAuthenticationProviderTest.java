package interview.guide.modules.auth.security;

import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.model.UserPasswordCredentialEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.repository.UserPasswordCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailPasswordAuthenticationProviderTest {
    private UserAccountRepository users;
    private UserPasswordCredentialRepository credentials;
    private PasswordEncoder passwordEncoder;
    private EmailPasswordAuthenticationProvider provider;
    private UserAccountEntity account;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        credentials = mock(UserPasswordCredentialRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        provider = new EmailPasswordAuthenticationProvider(users, credentials, passwordEncoder);

        account = new UserAccountEntity();
        account.setId(7L);
        account.setPublicId(UUID.randomUUID());
        account.setEmailNormalized("person@example.com");
        account.setDisplayName("Person");
        account.setStatus(UserAccountStatus.ACTIVE);
        account.setRole(UserRole.USER);
    }

    @Test
    void authenticatesByNormalizedEmailAndReturnsServerOwnedUserIdentity() {
        UserPasswordCredentialEntity credential = new UserPasswordCredentialEntity();
        credential.setPasswordHash("stored-password-hash");
        when(users.findByEmailNormalized("person@example.com")).thenReturn(Optional.of(account));
        when(credentials.findByUserId(7L)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("correct-password", "stored-password-hash")).thenReturn(true);

        var result = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                " Person@Example.com ", "correct-password"));

        AuthenticatedUser principal = (AuthenticatedUser) result.getPrincipal();
        assertTrue(result.isAuthenticated());
        assertEquals(7L, principal.id());
        assertEquals("person@example.com", principal.email());
        verify(users).findByEmailNormalized("person@example.com");
    }

    @Test
    void refusesCorrectPasswordUntilEmailIsVerified() {
        account.setStatus(UserAccountStatus.PENDING_EMAIL_VERIFICATION);
        UserPasswordCredentialEntity credential = new UserPasswordCredentialEntity();
        credential.setPasswordHash("stored-password-hash");
        when(users.findByEmailNormalized("person@example.com")).thenReturn(Optional.of(account));
        when(credentials.findByUserId(7L)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("correct-password", "stored-password-hash")).thenReturn(true);

        assertThrows(BadCredentialsException.class, () -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("person@example.com", "correct-password")));
    }
}
