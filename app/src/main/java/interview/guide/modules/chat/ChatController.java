package interview.guide.modules.chat;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.chat.model.ChatSessionEntity;
import interview.guide.modules.chat.model.ChatMessageEntity;
import interview.guide.modules.chat.service.ChatMessageService;
import interview.guide.modules.chat.service.ChatSessionService;
import interview.guide.modules.chat.service.ChatStreamService;
import interview.guide.modules.chat.service.ChatSummaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
  private final ChatSessionService sessionService;
  private final ChatMessageService messageService;
  private final ChatStreamService streamService;
  private final ChatSummaryService summaryService;

  @PostMapping("/sessions")
  public Result<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
    return Result.success(SessionResponse.from(sessionService.createSession(request.title())));
  }

  @GetMapping("/sessions")
  public Result<List<SessionResponse>> listSessions() {
    return Result.success(sessionService.listSessions().stream().map(SessionResponse::from).toList());
  }

  @GetMapping("/sessions/{sessionId}/messages")
  public Result<List<MessageResponse>> listMessages(@PathVariable Long sessionId) {
    return Result.success(messageService.listMessages(sessionId).stream().map(MessageResponse::from).toList());
  }

  @GetMapping("/sessions/{sessionId}")
  public Result<SessionDetailResponse> getSession(@PathVariable Long sessionId,
                                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
    int boundedPage = Math.max(0, page);
    int boundedSize = Math.min(100, Math.max(1, size));
    ChatSessionEntity session = sessionService.getSession(sessionId);
    var messages = messageService.listMessagesPage(sessionId, PageRequest.of(boundedPage, boundedSize));
    return Result.success(new SessionDetailResponse(SessionResponse.from(session), messages.getContent().stream().map(MessageResponse::from).toList(),
        messages.getNumber(), messages.getSize(), messages.getTotalElements(), messages.getTotalPages()));
  }
  @PatchMapping("/sessions/{sessionId}/pinned")
  public Result<Void> setPinned(@PathVariable Long sessionId, @RequestBody SetPinnedRequest request) { sessionService.setPinned(sessionId, request.pinned()); return Result.success(); }
  @PatchMapping("/sessions/{sessionId}/title")
  public Result<Void> rename(@PathVariable Long sessionId, @Valid @RequestBody RenameSessionRequest request) { sessionService.rename(sessionId, request.title()); return Result.success(); }
  @PostMapping("/sessions/{sessionId}/archive")
  public Result<Void> archive(@PathVariable Long sessionId) { sessionService.archive(sessionId); return Result.success(); }
  @DeleteMapping("/sessions/{sessionId}")
  public Result<Void> delete(@PathVariable Long sessionId) { sessionService.delete(sessionId); return Result.success(); }

  @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
  public Flux<ServerSentEvent<String>> streamMessage(
      @PathVariable Long sessionId, @Valid @RequestBody SendMessageRequest request) {
    Long assistantMessageId = messageService.prepareStreamMessage(sessionId, request.content());
    StringBuilder fullContent = new StringBuilder();
    return streamService.streamReply(sessionId, request.content())
        .doOnNext(fullContent::append)
        .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
        .doOnComplete(() -> {
      messageService.completeStreamMessage(assistantMessageId, fullContent.toString());
      summaryService.updateSummaryIfNeeded(sessionId);
    })
        .doOnError(error -> messageService.failStreamMessage(assistantMessageId, "AI_SERVICE_ERROR"));
  }
  @PostMapping(value = "/sessions/{sessionId}/messages/{messageId}/retry", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Flux<ServerSentEvent<String>> retryMessage(@PathVariable Long sessionId, @PathVariable Long messageId) {
    ChatMessageService.RetryRequest retry = messageService.retryFailedAssistant(messageId);
    if (!retry.sessionId().equals(sessionId)) throw new interview.guide.common.exception.BusinessException(interview.guide.common.exception.ErrorCode.BAD_REQUEST, "消息不属于当前会话");
    StringBuilder fullContent = new StringBuilder();
    return streamService.streamReply(sessionId, retry.userContent())
        .doOnNext(fullContent::append)
        .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
        .doOnComplete(() -> messageService.completeStreamMessage(retry.assistantMessageId(), fullContent.toString()))
        .doOnError(error -> messageService.failStreamMessage(retry.assistantMessageId(), "AI_SERVICE_ERROR"));
  }

  public record CreateSessionRequest(String title) {}
  public record SendMessageRequest(@NotBlank String content) {}
  public record SetPinnedRequest(boolean pinned) {}
  public record RenameSessionRequest(@NotBlank String title) {}
  public record SessionResponse(Long id, String title, boolean pinned, String providerId,
                                String status, int messageCount, LocalDateTime updatedAt) {
    static SessionResponse from(ChatSessionEntity session) {
      return new SessionResponse(session.getId(), session.getTitle(), session.isPinned(), session.getProviderId(),
          session.getStatus().name(), session.getMessageCount(), session.getUpdatedAt());
    }
  }
  public record MessageResponse(Long id, String role, String content, boolean completed, String failureCode,
                                int messageOrder, LocalDateTime createdAt) {
    static MessageResponse from(ChatMessageEntity message) {
      return new MessageResponse(message.getId(), message.getRole().name().toLowerCase(), message.getContent(),
          message.isCompleted(), message.getFailureCode(), message.getMessageOrder(), message.getCreatedAt());
    }
  }
  public record SessionDetailResponse(SessionResponse session, List<MessageResponse> messages, int page, int size,
                                      long totalElements, int totalPages) {}
}
