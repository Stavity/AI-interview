package interview.guide.modules.multiround;

import interview.guide.common.result.Result;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.annotation.RateLimit;
import interview.guide.common.ai.agent.InterviewAgentOrchestrator;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.service.AlgorithmSubmissionService;
import interview.guide.modules.multiround.service.AlgorithmTemplateService;
import interview.guide.modules.multiround.service.MultiRoundInterviewService;
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
import interview.guide.modules.multiround.service.MultiRoundWorkflowService;
import interview.guide.modules.multiround.service.MultiRoundFinalReportService;
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
  private final AgentRuntimeProperties agentRuntimeProperties;
  private final MultiRoundWorkflowService workflowService;
  private final MultiRoundFinalReportService finalReportService;

  @PostMapping("/interviews/{sessionId}")
  public Result<InterviewResponse> createInterview(@PathVariable String sessionId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    throw new BusinessException(ErrorCode.MULTI_ROUND_RESUME_REQUIRED, "请通过简历详情启动多轮面试");
  }
  @GetMapping("/interviews/{sessionId}")
  public Result<InterviewDetailResponse> getInterview(@PathVariable String sessionId) {
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    return Result.success(new InterviewDetailResponse(InterviewResponse.from(interview), roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId()).stream().map(round -> new RoundResponse(round.getId(), round.getRoundNo(), round.getRoundType().name(), round.getStatus().name(), round.getQuestionLimit())).toList()));
  }
  @GetMapping("/interviews/{sessionId}/workflow")
  public Result<WorkflowResponse> workflow(@PathVariable String sessionId) {
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    var workflow = workflowService.recover(interview);
    return Result.success(new WorkflowResponse(workflow.getExecutionMode().name(), workflow.getStatus().name(),
        workflow.getCheckpointKey(), workflow.getAdkSessionId()));
  }
  @PostMapping("/interviews/{sessionId}/presence")
  public Result<WorkflowResponse> presence(@PathVariable String sessionId) {
    MultiRoundInterviewEntity interview = interviewService.getBySessionId(sessionId);
    var workflow = workflowService.touch(interview);
    return Result.success(new WorkflowResponse(workflow.getExecutionMode().name(), workflow.getStatus().name(),
        workflow.getCheckpointKey(), workflow.getAdkSessionId()));
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
    MultiRoundReportService.Report report = finalReportService.find(interviewService.getBySessionId(sessionId));
    return Result.success(new ReportResponse(report.status(), report.completedRounds(), report.totalRounds(), report.summary(), report.source(), report.rounds()));
  }
  @GetMapping("/interviews/{sessionId}/agent-trace")
  public Result<List<AgentTraceResponse>> agentTrace(@PathVariable String sessionId) {
    return Result.success(traceService.list(interviewService.getBySessionId(sessionId).getId()).stream().map(AgentTraceResponse::from).toList());
  }
  @GetMapping("/agent-runtime")
  public Result<AgentRuntimeResponse> agentRuntime() {
    return Result.success(AgentRuntimeResponse.from(agentRuntimeProperties));
  }
  @PostMapping("/rounds/{roundId}/questions")
  public Result<QuestionResponse> addQuestion(@PathVariable Long roundId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AddQuestionRequest request) {
    return Result.success(QuestionResponse.from(operationService.addQuestion(roundId, idempotencyKey, request.questionType(), request.categoryKey(), request.question(), request.referenceAnswer())));
  }
  @GetMapping("/rounds/{roundId}/questions")
  public Result<List<QuestionResponse>> listQuestions(@PathVariable Long roundId) { return Result.success(questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId).stream().map(QuestionResponse::from).toList()); }
  @PostMapping("/questions/{questionId}/answer")
  public Result<Void> answer(@PathVariable Long questionId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AnswerRequest request) { operationService.answer(questionId, idempotencyKey, request.answer()); return Result.success(); }

  @GetMapping("/rounds/{roundId}/report")
  public Result<MultiRoundRoundReportService.RoundReport> roundReport(@PathVariable Long roundId) {
    return Result.success(operationService.getRoundReport(roundId));
  }


  @GetMapping("/algorithm/templates")
  public Result<List<TemplateResponse>> templates() {
    return Result.success(List.of("JAVA", "PYTHON", "CPP").stream().map(language -> new TemplateResponse(language, "v1", templateService.templateFor(language), "AI 静态评审，未执行代码")).toList());
  }

  @PostMapping("/questions/{questionId}/algorithm-submissions")
  public Result<SubmissionResponse> submit(@PathVariable Long questionId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody SubmissionRequest request) {
    return Result.success(SubmissionResponse.from(operationService.submitAlgorithm(questionId, idempotencyKey, request.language(), request.sourceCode())));
  }
  @GetMapping("/algorithm-submissions/{submissionId}")
  public Result<SubmissionResponse> getSubmission(@PathVariable Long submissionId) {
    return Result.success(SubmissionResponse.from(submissionService.get(submissionId)));
  }
  @GetMapping("/questions/{questionId}/algorithm-submissions")
  public Result<SubmissionResponse> getSubmissionForQuestion(@PathVariable Long questionId) {
    return Result.success(submissionService.findByQuestionId(questionId).map(SubmissionResponse::from).orElse(null));
  }
  @PostMapping("/algorithm-submissions/{submissionId}/review") @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<StaticReviewResponse> reviewAlgorithm(@PathVariable Long submissionId) {
    return Result.success(StaticReviewResponse.from(operationService.reviewAlgorithm(submissionId)));
  }

  public record SubmissionRequest(@NotBlank String language, @NotBlank String sourceCode) {}
  public record TemplateResponse(String language, String templateVersion, String sourceCode, String notice) {}
  public record InterviewResponse(Long id, String status, int currentRoundNo, String providerId) {
    static InterviewResponse from(MultiRoundInterviewEntity value) { return new InterviewResponse(value.getId(), value.getStatus().name(), value.getCurrentRoundNo(), value.getProviderId()); }
  }
  public record RoundResponse(Long id, int roundNo, String roundType, String status, int questionLimit) { static RoundResponse from(interview.guide.modules.multiround.model.MultiRoundEntity value) { return new RoundResponse(value.getId(), value.getRoundNo(), value.getRoundType().name(), value.getStatus().name(), value.getQuestionLimit()); } }
  public record InterviewDetailResponse(InterviewResponse interview, List<RoundResponse> rounds) {}
  public record WorkflowResponse(String executionMode, String status, String checkpointKey, String adkSessionId) {}
  public record AddQuestionRequest(@NotBlank String questionType, String categoryKey, @NotBlank String question, String referenceAnswer) {}
  public record AnswerRequest(@NotBlank String answer) {}
  public record QuestionResponse(Long id, int sequenceNo, String questionType, String categoryKey, String question, String status, String answer, String problemJson) { static QuestionResponse from(interview.guide.modules.multiround.model.MultiRoundQuestionEntity value) { return new QuestionResponse(value.getId(), value.getSequenceNo(), value.getQuestionType(), value.getCategoryKey(), value.getQuestion(), value.getStatus().name(), value.getAnswer(), value.getProblemJson()); } }
  public record AgentPlanRequest(@NotBlank String trigger, @NotBlank @Size(max = 12000) String context) {}
  public record AgentPlanResponse(int roundNo, String roundType, int followUpCount, List<String> categoryKeys, int stepCount) {}
  public record AgentRuntimeResponse(String runtime, String policyVersion) {
    static AgentRuntimeResponse from(AgentRuntimeProperties value) {
      return new AgentRuntimeResponse(value.getRuntime().name(), value.getPolicyVersion());
    }
  }
  public record ReportResponse(String status, int completedRounds, int totalRounds, String summary, String source,
                               List<MultiRoundRoundReportService.RoundReport> rounds) {}
  public record AgentTraceStepResponse(int stepNo, String eventType, String toolName, String status,
      String outputSummary, Long durationMs, java.time.LocalDateTime createdAt) {
    static AgentTraceStepResponse from(AgentRunTraceService.StepTrace value) {
      return new AgentTraceStepResponse(value.stepNo(), value.eventType(), value.toolName(), value.status(),
          value.outputSummary(), value.durationMs(), value.createdAt());
    }
  }
  public record AgentTraceResponse(Long id, String triggerType, String status, String errorCode,
      String runtimeType, String runtimeVersion, String policyVersion, java.time.LocalDateTime startedAt,
      java.time.LocalDateTime finishedAt, List<AgentTraceStepResponse> steps) {
    static AgentTraceResponse from(AgentRunTraceService.RunTrace value) {
      return new AgentTraceResponse(value.id(), value.triggerType(), value.status(), value.errorCode(),
          value.runtimeType(), value.runtimeVersion(), value.policyVersion(), value.startedAt(),
          value.finishedAt(), value.steps().stream().map(AgentTraceStepResponse::from).toList());
    }
  }
  public record SubmissionResponse(Long id, Long questionId, String language, String sourceCode, String templateVersion, String evaluationStatus, String evaluationErrorCode, String evaluation, String notice) {
    static SubmissionResponse from(AlgorithmSubmissionEntity value) { return new SubmissionResponse(value.getId(), value.getQuestion().getId(), value.getLanguage().name(), value.getSourceCode(), value.getTemplateVersion(), value.getEvaluationStatus().name(), value.getEvaluationErrorCode(), value.getEvaluationJson(), "AI 静态评审，未执行代码"); }
  }
  public record StaticReviewResponse(int score, boolean correctnessPassed, boolean complexityPassed, String requiredTimeComplexity, String requiredSpaceComplexity, String approach, String timeComplexity, String spaceComplexity, List<String> boundaryCases, List<String> blockingIssues, List<String> qualityIssues, List<String> improvements, String notice) { static StaticReviewResponse from(AlgorithmStaticReviewService.Review value) { return new StaticReviewResponse(value.score(), value.correctnessPassed(), value.complexityPassed(), value.requiredTimeComplexity(), value.requiredSpaceComplexity(), value.approach(), value.timeComplexity(), value.spaceComplexity(), value.boundaryCases(), value.blockingIssues(), value.qualityIssues(), value.improvements(), "本结果为 AI 静态评审，未执行、编译或验证代码。"); } }
}
