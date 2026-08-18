package interview.guide.modules.learning;

import interview.guide.common.result.Result;
import interview.guide.modules.learning.model.LearningPlanEntity;
import interview.guide.modules.learning.model.LearningReminderEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.model.LearningProgressEntity;
import interview.guide.modules.learning.repository.LearningProgressRepository;
import interview.guide.modules.learning.repository.LearningReminderRepository;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import interview.guide.modules.learning.service.LearningPlanService;
import interview.guide.modules.learning.service.LearningProgressService;
import interview.guide.modules.learning.service.LearningRecommendationService;
import interview.guide.modules.learning.service.LearningReminderService;
import interview.guide.modules.multiround.service.MultiRoundInterviewService;
import interview.guide.modules.multiround.service.MultiRoundReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

  private final LearningSkillSourceRepository skillSourceRepository;
  private final LearningPlanService planService;
  private final LearningReminderRepository reminderRepository;
  private final LearningProgressRepository progressRepository;
  private final LearningProgressService progressService;
  private final LearningRecommendationService recommendationService;
  private final LearningReminderService reminderService;
  private final MultiRoundInterviewService multiRoundInterviewService;
  private final MultiRoundReportService multiRoundReportService;

  @GetMapping("/sources")
  public Result<List<SkillSourceResponse>> listSources() {
    return Result.success(skillSourceRepository.findAll().stream().map(SkillSourceResponse::from).toList());
  }
  @GetMapping("/progress")
  public Result<List<ProgressResponse>> listProgress() { return Result.success(progressRepository.findAll().stream().map(ProgressResponse::from).toList()); }
  @GetMapping("/path-recommendation")
  public Result<List<RecommendationResponse>> pathRecommendation() {
    return Result.success(recommendationService.recommend().stream().map(RecommendationResponse::from).toList());
  }
  @PostMapping("/recommendations/from-interview/{sessionId}")
  public Result<InterviewRecommendationPreviewResponse> recommendationsFromInterview(@PathVariable String sessionId) {
    var interview = multiRoundInterviewService.getBySessionId(sessionId);
    var report = multiRoundReportService.report(interview);
    return Result.success(new InterviewRecommendationPreviewResponse(report.summary(), report.completedRounds(), report.totalRounds(),
        recommendationService.recommend().stream().map(RecommendationResponse::from).toList()));
  }
  @PostMapping("/sources/{sourceId}/progress")
  public Result<ProgressResponse> adjustProgress(@PathVariable Long sourceId, @Valid @RequestBody AdjustProgressRequest request) {
    LearningSkillSourceEntity source = skillSourceRepository.findById(sourceId).orElseThrow(() -> new interview.guide.common.exception.BusinessException(interview.guide.common.exception.ErrorCode.NOT_FOUND, "学习技能来源不存在: " + sourceId));
    return Result.success(ProgressResponse.from(progressService.adjustMastery(source, request.delta())));
  }

  @GetMapping("/plans")
  public Result<List<PlanResponse>> listPlans() {
    return Result.success(planService.listPlans().stream().map(PlanResponse::from).toList());
  }

  @PostMapping("/plans")
  public Result<PlanResponse> createPlan(@Valid @RequestBody CreatePlanRequest request) {
    return Result.success(PlanResponse.from(
        planService.createDraft(request.title(), request.goal(), request.startDate(), request.endDate())));
  }

  @PostMapping("/plans/{planId}/activate")
  public Result<PlanResponse> activatePlan(@PathVariable Long planId) {
    return Result.success(PlanResponse.from(planService.activate(planId)));
  }

  @GetMapping("/plans/{planId}/tasks")
  public Result<List<TaskResponse>> listTasks(@PathVariable Long planId) {
    return Result.success(planService.listTasks(planId).stream().map(TaskResponse::from).toList());
  }

  @PostMapping("/plans/{planId}/tasks")
  public Result<TaskResponse> createTask(@PathVariable Long planId, @Valid @RequestBody CreateTaskRequest request) {
    return Result.success(TaskResponse.from(planService.createTask(planId, request.title(), request.dueDate(),
        request.estimatedMinutes(), request.skillSourceId(), request.remindAt())));
  }

  @PostMapping("/tasks/{taskId}/complete")
  public Result<Void> completeTask(@PathVariable Long taskId) {
    planService.completeTask(taskId);
    return Result.success();
  }

  @GetMapping("/reminders/ready")
  public Result<List<ReminderResponse>> listReadyReminders() {
    return Result.success(reminderRepository.findByStatusOrderByRemindAtAsc(LearningReminderEntity.Status.READY)
        .stream().map(ReminderResponse::from).toList());
  }
  @PostMapping("/reminders/{reminderId}/delivery")
  public Result<Void> acknowledgeReminder(@PathVariable Long reminderId, @Valid @RequestBody ReminderDeliveryRequest request) {
    reminderService.acknowledge(reminderId, request.status(), request.failureReason());
    return Result.success();
  }

  public record CreatePlanRequest(
      @NotBlank String title,
      String goal,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate
  ) {}
  public record AdjustProgressRequest(@NotNull Integer delta) {}

  public record CreateTaskRequest(
      @NotBlank String title,
      @NotNull LocalDate dueDate,
      @NotNull Integer estimatedMinutes,
      Long skillSourceId,
      LocalDateTime remindAt
  ) {}

  public record SkillSourceResponse(Long id, String skillId, String categoryKey, String displayName,
                                    String description, String status, LocalDateTime syncedAt) {
    static SkillSourceResponse from(LearningSkillSourceEntity source) {
      return new SkillSourceResponse(source.getId(), source.getSkillId(), source.getCategoryKey(),
          source.getDisplayName(), source.getDescription(), source.getStatus().name(), source.getSyncedAt());
    }
  }

  public record PlanResponse(Long id, String title, String goal, LocalDate startDate, LocalDate endDate,
                             String status) {
    static PlanResponse from(LearningPlanEntity plan) {
      return new PlanResponse(plan.getId(), plan.getTitle(), plan.getGoal(), plan.getStartDate(),
          plan.getEndDate(), plan.getStatus().name());
    }
  }

  public record TaskResponse(Long id, Long planId, Long skillSourceId, String title, LocalDate dueDate,
                             int estimatedMinutes, String status, LocalDateTime completedAt) {
    static TaskResponse from(interview.guide.modules.learning.model.LearningPlanTaskEntity task) {
      return new TaskResponse(task.getId(), task.getPlan().getId(),
          task.getSkillSource() == null ? null : task.getSkillSource().getId(), task.getTitle(), task.getDueDate(),
          task.getEstimatedMinutes(), task.getStatus().name(), task.getCompletedAt());
    }
  }
  public record ProgressResponse(Long id, Long sourceId, int masteryScore, String status, LocalDateTime lastPracticedAt) {
    static ProgressResponse from(LearningProgressEntity progress) { return new ProgressResponse(progress.getId(), progress.getSkillSource().getId(), progress.getMasteryScore(), progress.getStatus().name(), progress.getLastPracticedAt()); }
  }
  public record RecommendationResponse(Long sourceId, String skillId, String categoryKey, String displayName, int masteryScore, String reason) {
    static RecommendationResponse from(LearningRecommendationService.Recommendation value) { return new RecommendationResponse(value.sourceId(), value.skillId(), value.categoryKey(), value.displayName(), value.masteryScore(), value.reason()); }
  }
  public record InterviewRecommendationPreviewResponse(String reportSummary, int completedRounds, int totalRounds, List<RecommendationResponse> recommendations) {}

  public record ReminderResponse(Long id, Long taskId, LocalDateTime remindAt, String channel, String status) {
    static ReminderResponse from(LearningReminderEntity reminder) {
      return new ReminderResponse(reminder.getId(), reminder.getTask().getId(), reminder.getRemindAt(),
          reminder.getChannel().name(), reminder.getStatus().name());
    }
  }
  public record ReminderDeliveryRequest(@NotNull LearningReminderEntity.Status status, @Size(max = 500) String failureReason) {}
}
