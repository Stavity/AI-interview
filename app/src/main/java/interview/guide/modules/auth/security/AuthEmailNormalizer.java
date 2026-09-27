package interview.guide.modules.auth.security;

import java.util.Locale;

public final class AuthEmailNormalizer {
    private AuthEmailNormalizer() {}

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
