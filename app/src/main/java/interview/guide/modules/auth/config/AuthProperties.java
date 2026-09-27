package interview.guide.modules.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private String frontendBaseUrl = "http://localhost:5173";
    private boolean mailEnabled;
    private String mailFrom = "no-reply@example.com";
    private boolean sessionCookieSecure;
    private Duration verificationTokenTtl = Duration.ofHours(24);
    private Duration passwordResetTokenTtl = Duration.ofMinutes(30);
}
