package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.security.AuthenticatedUser;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedWebSocketHandshakeInterceptorTest {
  private final VoiceInterviewSessionRepository repository = mock(VoiceInterviewSessionRepository.class);
  private final AuthenticatedWebSocketHandshakeInterceptor interceptor =
      new AuthenticatedWebSocketHandshakeInterceptor(repository);
  private final ServerHttpResponse response = mock(ServerHttpResponse.class);
  private final WebSocketHandler handler = mock(WebSocketHandler.class);

  @Test
  void acceptsOwnedSessionAndPinsOwnerInWebSocketAttributes() {
    ServerHttpRequest request = authenticatedRequest(7L, "/ws/voice-interview/41");
    when(repository.existsByIdAndOwnerUserId(41L, 7L)).thenReturn(true);
    var attributes = new HashMap<String, Object>();

    assertThat(interceptor.beforeHandshake(request, response, handler, attributes)).isTrue();
    assertThat(attributes).containsEntry(
        AuthenticatedWebSocketHandshakeInterceptor.OWNER_USER_ID_ATTRIBUTE, 7L);
  }

  @Test
  void rejectsForeignSessionWithoutLeakingOwnerIntoAttributes() {
    ServerHttpRequest request = authenticatedRequest(8L, "/ws/voice-interview/41");
    when(repository.existsByIdAndOwnerUserId(41L, 8L)).thenReturn(false);
    var attributes = new HashMap<String, Object>();

    assertThat(interceptor.beforeHandshake(request, response, handler, attributes)).isFalse();
    assertThat(attributes).doesNotContainKey(
        AuthenticatedWebSocketHandshakeInterceptor.OWNER_USER_ID_ATTRIBUTE);
  }

  @Test
  void rejectsAnonymousAndMalformedSessionBeforeDatabaseLookup() {
    ServerHttpRequest anonymous = mock(ServerHttpRequest.class);
    var attributes = new HashMap<String, Object>();
    assertThat(interceptor.beforeHandshake(anonymous, response, handler, attributes)).isFalse();

    ServerHttpRequest malformed = authenticatedRequest(9L, "/ws/voice-interview/not-a-number");
    assertThat(interceptor.beforeHandshake(malformed, response, handler, attributes)).isFalse();
    verifyNoInteractions(repository);
  }

  private static ServerHttpRequest authenticatedRequest(Long userId, String path) {
    AuthenticatedUser user = new AuthenticatedUser(
        userId, UUID.randomUUID(), "owner@example.test", "Owner", UserRole.USER);
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        user, null, user.getAuthorities());
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPrincipal()).thenReturn(authentication);
    when(request.getURI()).thenReturn(URI.create("http://localhost" + path));
    return request;
  }
}
