package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.auth.security.AuthenticatedUser;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Refuse unauthenticated handshakes and sessions owned by a different account. */
@Component
@RequiredArgsConstructor
public class AuthenticatedWebSocketHandshakeInterceptor implements HandshakeInterceptor {
  public static final String OWNER_USER_ID_ATTRIBUTE = "ownerUserId";

  private final VoiceInterviewSessionRepository sessionRepository;

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                 WebSocketHandler wsHandler, Map<String, Object> attributes) {
    if (!(request.getPrincipal() instanceof Authentication authentication)
        || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
        || user.id() == null || user.id() <= 0) {
      return false;
    }
    String path = request.getURI().getPath();
    String finalSegment = path.substring(path.lastIndexOf('/') + 1);
    try {
      Long sessionId = Long.valueOf(finalSegment);
      if (!sessionRepository.existsByIdAndOwnerUserId(sessionId, user.id())) {
        return false;
      }
      attributes.put(OWNER_USER_ID_ATTRIBUTE, user.id());
      return true;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                             WebSocketHandler wsHandler, Exception exception) {
    // No post-handshake state is needed.
  }
}
