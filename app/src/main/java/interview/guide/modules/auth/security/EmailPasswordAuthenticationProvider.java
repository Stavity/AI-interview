package interview.guide.modules.auth.security;

import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.repository.UserPasswordCredentialRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class EmailPasswordAuthenticationProvider implements AuthenticationProvider {
    private static final String DUMMY_PASSWORD = "not-a-real-account-password-" +
            "7e03c0f4-7cf9-46fa-a148-a71a0a19fd70";

    private final UserAccountRepository userRepository;
    private final UserPasswordCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public EmailPasswordAuthenticationProvider(UserAccountRepository userRepository,
                                               UserPasswordCredentialRepository credentialRepository,
                                               PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = AuthEmailNormalizer.normalize(authentication.getName());
        String rawPassword = String.valueOf(authentication.getCredentials());
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            passwordEncoder.matches(DUMMY_PASSWORD, dummyPasswordHash);
            throw new BadCredentialsException("Invalid credentials");
        }
        UserAccountEntity account = userRepository.findByEmailNormalized(email).orElse(null);
        if (account == null) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            throw new BadCredentialsException("Invalid credentials");
        }

        String passwordHash = credentialRepository.findByUserId(account.getId())
                .map(credential -> credential.getPasswordHash())
                .orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, passwordHash);
        if (!passwordMatches || account.getStatus() != UserAccountStatus.ACTIVE) {
            throw new BadCredentialsException("Invalid credentials");
        }

        AuthenticatedUser principal = new AuthenticatedUser(
                account.getId(), account.getPublicId(), account.getEmailNormalized(),
                account.getDisplayName(), account.getRole());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
