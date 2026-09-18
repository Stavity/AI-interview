package interview.guide.modules.multiround;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.multiround.dto.MultiRoundInterviewBootstrapResponse;
import interview.guide.modules.multiround.dto.StartMultiRoundInterviewRequest;
import interview.guide.modules.multiround.service.MultiRoundInterviewBootstrapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ResumeMultiRoundController {
  private final MultiRoundInterviewBootstrapService bootstrapService;

  @PostMapping("/api/resumes/{resumeId}/multi-round-interviews")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<MultiRoundInterviewBootstrapResponse> start(
      @PathVariable Long resumeId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody StartMultiRoundInterviewRequest request) {
    return Result.success(bootstrapService.start(resumeId, request, idempotencyKey));
  }

  @GetMapping("/api/resumes/{resumeId}/multi-round-interviews/active")
  public Result<MultiRoundInterviewBootstrapResponse> active(@PathVariable Long resumeId) {
    return Result.success(bootstrapService.findActive(resumeId).orElse(null));
  }
}
