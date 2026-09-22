package interview.guide.modules.multiround;

import interview.guide.common.result.Result;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.annotation.RateLimit;
import interview.guide.common.ai.agent.InterviewAgentOrchestrator;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.service.AlgorithmSubmissionService;
import interview.guide.modules.multiround.service.AlgorithmTemplateService;
import interview.guide.modules.multiround.service.MultiRoundInterviewService;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.service.MultiRoundQuestionService;
import interview.guide.modules.multiround.service.MultiRoundReportService;
import interview.guide.modules.multiround.service.MultiRoundRoundReportService;
import interview.guide.modules.multiround.service.MultiRoundOperationService;
import interview.guide.modules.multiround.service.AgentRunTraceService;
import interview.guide.modules.multiround.service.AlgorithmSubmissionReviewService;
import interview.guide.modules.multiround.service.AlgorithmStaticReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/multi-round") @RequiredArgsConstructor
public class MultiRoundController {
  private final AlgorithmTemplateService templateService;
  private final AlgorithmSubmissionService submissionService;
  private final MultiRoundInterviewService interviewService;
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  private final MultiRoundQuestionService questionService;
  private final MultiRoundOperationService operationService;
  private final InterviewAgentOrchestrator agentOrchestrator;
  private final MultiRoundReportService reportService;
  private final AgentRunTraceService traceService;
  private final AlgorithmSubmissionReviewService algorithmReviewService;
  private final interview.guide.modules.multiround.service.MultiRoundWorkflowService workflowService;
  private final interview.guide.modules.multiround.service.MultiRoundAutomaticStartService automaticStartService;

  @GetMapping("/interviews/{sessionId}")
  public Result<InterviewDetailResponse> getInterview(@PathVariable String sessionId) {
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    return Result.success(new InterviewDetailResponse(InterviewResponse.from(interview), roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId()).stream().map(round -> new RoundResponse(round.getId(), round.getRoundNo(), round.getRoundType().name(), round.getStatus().name(), round.getQuestionLimit())).toList()));
  }
  @GetMapping("/interviews/{sessionId}/workflow")
  public Result<interview.guide.modules.multiround.service.MultiRoundWorkflowService.WorkflowView> workflow(
      @PathVariable String sessionId) {
    return Result.success(workflowService.view(interviewService.getBySessionId(sessionId)));
  }
  @PostMapping("/interviews/{sessionId}/recover")
  public Result<interview.guide.modules.multiround.service.MultiRoundWorkflowService.WorkflowView> recover(
      @PathVariable String sessionId) {
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    if (workflowService.resume(interview)) automaticStartService.ensureNextPendingNodeStarted(interview);
    return Result.success(workflowService.view(interview));
  }
  @PostMapping("/interviews/{sessionId}/agent-plan") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<AgentPlanResponse> agentPlan(@PathVariable String sessionId, @Valid @RequestBody AgentPlanRequest request) {
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    operationService.requireResumeBound(interview);
    InterviewPlan plan = agentOrchestrator.plan(interview, request.trigger(), request.context());
    return Result.success(new AgentPlanResponse(plan.roundNo(), plan.roundType(), plan.followUpCount(), plan.categoryKeys(), plan.stepCount()));
  }
  @GetMapping("/interviews/{sessionId}/report")
  public Result<ReportResponse> report(@PathVariable String sessionId) {
    MultiRoundReportService.Report report = reportService.report(interviewService.getBySessionId(sessionId));
    return Result.success(new ReportResponse(report.status(), report.completedRounds(), report.totalRounds(), report.summary(), report.source(), report.rounds(), report.advice()));
  }
  @GetMapping("/interviews/{sessionId}/agent-trace")
  public Result<List<AgentTraceResponse>> agentTrace(@PathVariable String sessionId) {
    return Result.success(traceService.list(interviewService.getBySessionId(sessionId).getId()).stream().map(AgentTraceResponse::from).toList());
  }
  @PostMapping("/rounds/{roundId}/questions")
  public Result<QuestionResponse> addQuestion(@PathVariable Long roundId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AddQuestionRequest request) {
    return Result.success(QuestionResponse.from(operationService.addQuestion(roundId, idempotencyKey, request.questionType(), request.categoryKey(), request.question(), request.referenceAnswer())));
  }
  @GetMapping("/rounds/{roundId}/questions")
  public Result<List<QuestionResponse>> listQuestions(@PathVariable Long roundId) { return Result.success(questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId).stream().map(QuestionResponse::from).toList()); }
  @PostMapping("/questions/{questionId}/answer")
  public Result<Void> answer(@PathVariable Long questionId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AnswerRequest request) { operationService.answer(questionId, idempotencyKey, request.answer()); return Result.success(); }

  @PostMapping("/rounds/{roundId}/start") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<RoundResponse> startRound(@PathVariable Long roundId, @RequestHeader("Idempotency-Key") String idempotencyKey) { return Result.success(RoundResponse.from(operationService.startRound(roundId, idempotencyKey))); }

  @PostMapping("/rounds/{roundId}/finish") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<RoundResponse> finishRound(@PathVariable Long roundId, @RequestHeader("Idempotency-Key") String idempotencyKey) { return Result.success(RoundResponse.from(operationService.finishRound(roundId, idempotencyKey))); }

  @PostMapping("/interviews/{sessionId}/next-round") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<RoundResponse> nextRound(@PathVariable String sessionId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    MultiRoundEntity next = operationService.nextRound(sessionId, idempotencyKey);
    return Result.success(next != null ? RoundResponse.from(next) : null);
  }

  @GetMapping("/rounds/{roundId}/report")
  public Result<MultiRoundRoundReportService.RoundReport> roundReport(@PathVariable Long roundId) {
    return Result.success(operationService.getRoundReport(roundId));
  }


  @GetMapping("/algorithm/templates")
  public Result<List<TemplateResponse>> templates() {
    return Result.success(List.of("JAVA", "PYTHON", "CPP").stream().map(language -> new TemplateResponse(language, "v1", templateService.templateFor(language), "AI 静态评审，未执行代码")).toList());
  }

  @PostMapping("/questions/{questionId}/algorithm-submissions") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<SubmissionResponse> submit(@PathVariable Long questionId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody SubmissionRequest request) {
    return Result.success(SubmissionResponse.from(operationService.submitAlgorithm(questionId, idempotencyKey, request.language(), request.sourceCode())));
  }
  @GetMapping("/algorithm-submissions/{submissionId}")
  public Result<SubmissionResponse> getSubmission(@PathVariable Long submissionId) {
    return Result.success(SubmissionResponse.from(submissionService.get(submissionId)));
  }
  @PostMapping("/algorithm-submissions/{submissionId}/review") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<StaticReviewResponse> reviewAlgorithm(@PathVariable Long submissionId) {
    AlgorithmSubmissionEntity submission = submissionService.get(submissionId);
    operationService.requireResumeBound(submission.getQuestion().getRound().getInterview());
    StaticReviewResponse response = StaticReviewResponse.from(algorithmReviewService.review(submissionId));
    operationService.progressAfterCandidateAction(submission.getQuestion().getRound().getId());
    return Result.success(response);
  }

  // 请求体上限与服务层校验对齐，避免超大载荷先被完整反序列化进内存再被拒
  public record SubmissionRequest(@NotBlank @Size(max = 16) String language,
                                  @NotBlank @Size(max = 50_000) String sourceCode) {}
  public record TemplateResponse(String language, String templateVersion, String sourceCode, String notice) {}
  public record InterviewResponse(Long id, String status, int currentRoundNo, String providerId) {
    static InterviewResponse from(MultiRoundInterviewEntity value) { return new InterviewResponse(value.getId(), value.getStatus().name(), value.getCurrentRoundNo(), value.getProviderId()); }
  }
  public record RoundResponse(Long id, int roundNo, String roundType, String status, int questionLimit) { static RoundResponse from(interview.guide.modules.multiround.model.MultiRoundEntity value) { return new RoundResponse(value.getId(), value.getRoundNo(), value.getRoundType().name(), value.getStatus().name(), value.getQuestionLimit()); } }
  public record InterviewDetailResponse(InterviewResponse interview, List<RoundResponse> rounds) {}
  public record AddQuestionRequest(@NotBlank @Size(max = 32) String questionType,
                                   @Size(max = 128) String categoryKey,
                                   @NotBlank @Size(max = 8_000) String question,
                                   @Size(max = 8_000) String referenceAnswer) {}
  public record AnswerRequest(@NotBlank @Size(max = 20_000) String answer) {}
  public record QuestionResponse(Long id, int sequenceNo, String questionType, String categoryKey, String question, String status, String answer) { static QuestionResponse from(interview.guide.modules.multiround.model.MultiRoundQuestionEntity value) { return new QuestionResponse(value.getId(), value.getSequenceNo(), value.getQuestionType(), value.getCategoryKey(), value.getQuestion(), value.getStatus().name(), value.getAnswer()); } }
  public record AgentPlanRequest(@NotBlank String trigger, @NotBlank @Size(max = 12000) String context) {}
  public record AgentPlanResponse(int roundNo, String roundType, int followUpCount, List<String> categoryKeys, int stepCount) {}
  public record ReportResponse(String status, int completedRounds, int totalRounds, String summary, String source,
                               List<MultiRoundRoundReportService.RoundReport> rounds, MultiRoundReportService.Advice advice) {}
  public record AgentTraceStepResponse(int stepNo, String toolName, String status, String outputSummary, Long durationMs, java.time.LocalDateTime createdAt) { static AgentTraceStepResponse from(AgentRunTraceService.StepTrace value) { return new AgentTraceStepResponse(value.stepNo(), value.toolName(), value.status(), value.outputSummary(), value.durationMs(), value.createdAt()); } }
  public record AgentTraceResponse(Long id, String triggerType, String status, String errorCode, java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt, List<AgentTraceStepResponse> steps) { static AgentTraceResponse from(AgentRunTraceService.RunTrace value) { return new AgentTraceResponse(value.id(), value.triggerType(), value.status(), value.errorCode(), value.startedAt(), value.finishedAt(), value.steps().stream().map(AgentTraceStepResponse::from).toList()); } }
  public record SubmissionResponse(Long id, Long questionId, String language, String templateVersion, String evaluationStatus, String evaluationErrorCode, String evaluation, String notice) {
    static SubmissionResponse from(AlgorithmSubmissionEntity value) { return new SubmissionResponse(value.getId(), value.getQuestion().getId(), value.getLanguage().name(), value.getTemplateVersion(), value.getEvaluationStatus().name(), value.getEvaluationErrorCode(), value.getEvaluationJson(), "AI 静态评审，未执行代码"); }
  }
  public record StaticReviewResponse(int score, String approach, String timeComplexity, String spaceComplexity, List<String> boundaryCases, List<String> qualityIssues, List<String> improvements, String notice) { static StaticReviewResponse from(AlgorithmStaticReviewService.Review value) { return new StaticReviewResponse(Math.max(0, Math.min(100, value.score())), value.approach(), value.timeComplexity(), value.spaceComplexity(), value.boundaryCases(), value.qualityIssues(), value.improvements(), "本结果为 AI 静态评审，未执行、编译或验证代码。"); } }
}
