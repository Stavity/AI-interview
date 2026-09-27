package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 1, max = 80) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record EmailActionRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record TokenActionRequest(@NotBlank @Size(max = 128) String token) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {}

    public record CurrentUserResponse(String publicId, String email, String displayName, String role) {}

    public record CsrfTokenResponse(String token) {}
}
