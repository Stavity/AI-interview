package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.learning.model.LearningPlanEntity;
import interview.guide.modules.learning.model.LearningPlanTaskEntity;
import interview.guide.modules.learning.model.LearningReminderEntity;
import interview.guide.modules.learning.repository.LearningPlanRepository;
import interview.guide.modules.learning.repository.LearningPlanTaskRepository;
import interview.guide.modules.learning.repository.LearningReminderRepository;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningPlanService {

  private final LearningPlanRepository planRepository;
  private final LearningPlanTaskRepository taskRepository;
  private final LearningReminderRepository reminderRepository;
  private final LearningSkillSourceRepository skillSourceRepository;
  private final LearningProgressService progressService;
  private final LearningProperties learningProperties;

  @Transactional
  public LearningPlanEntity createDraft(String title, String goal, LocalDate startDate, LocalDate endDate) {
    if (endDate.isBefore(startDate)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "学习计划结束日期不能早于开始日期");
    }
    LearningPlanEntity plan = new LearningPlanEntity();
    plan.setTitle(title);
    plan.setGoal(goal);
    plan.setStartDate(startDate);
    plan.setEndDate(endDate);
    plan.setStatus(LearningPlanEntity.Status.DRAFT);
    return planRepository.save(plan);
  }

  @Transactional
  public LearningPlanEntity activate(Long planId) {
    LearningPlanEntity plan = planRepository.findById(planId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "学习计划不存在: " + planId));
    if (plan.getStatus() == LearningPlanEntity.Status.DRAFT) {
      plan.setStatus(LearningPlanEntity.Status.ACTIVE);
      return planRepository.save(plan);
    }
    if (plan.getStatus() != LearningPlanEntity.Status.ACTIVE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前学习计划不能被确认");
    }
    return plan;
  }

  @Transactional
  public void completeTask(Long taskId) {
    LearningPlanTaskEntity task = taskRepository.findById(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "学习任务不存在: " + taskId));
    boolean firstCompletion = task.getStatus() == LearningPlanTaskEntity.Status.PENDING;
    task.complete();
    taskRepository.save(task);

    if (firstCompletion && task.getSkillSource() != null) {
      progressService.adjustMastery(task.getSkillSource(), learningProperties.getTaskCompletionDelta());
    }

    List<LearningReminderEntity> reminders = reminderRepository.findByTaskAndStatusIn(
        task, List.of(LearningReminderEntity.Status.PENDING, LearningReminderEntity.Status.READY));
    reminders.forEach(LearningReminderEntity::cancelForCompletedTask);
    if (!reminders.isEmpty()) {
      reminderRepository.saveAll(reminders);
    }
  }

  @Transactional
  public LearningPlanTaskEntity createTask(Long planId, String title, LocalDate dueDate, int estimatedMinutes,
                                           Long skillSourceId, java.time.LocalDateTime remindAt) {
    LearningPlanEntity plan = planRepository.findById(planId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "学习计划不存在: " + planId));
    if (plan.getStatus() != LearningPlanEntity.Status.ACTIVE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先确认学习计划后再创建任务");
    }
    if (dueDate.isBefore(plan.getStartDate()) || dueDate.isAfter(plan.getEndDate())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "学习任务截止日期必须在计划周期内");
    }
    if (estimatedMinutes <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "学习任务预计时长必须大于 0");
    }
    LearningPlanTaskEntity task = LearningPlanTaskEntity.create(title, dueDate);
    task.setPlan(plan);
    task.setEstimatedMinutes(estimatedMinutes);
    if (skillSourceId != null) {
      task.setSkillSource(skillSourceRepository.findById(skillSourceId)
          .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "学习技能来源不存在: " + skillSourceId)));
    }
    task = taskRepository.save(task);
    if (remindAt != null) {
      reminderRepository.save(LearningReminderEntity.pending(task, remindAt));
    }
    return task;
  }

  public List<LearningPlanEntity> listPlans() {
    return planRepository.findAllByOrderByUpdatedAtDesc();
  }

  @Transactional(readOnly = true)
  public List<LearningPlanTaskEntity> listTasks(Long planId) {
    if (!planRepository.existsById(planId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "学习计划不存在: " + planId);
    }
    return taskRepository.findByPlanIdOrderByDueDateAscSortOrderAsc(planId);
  }
}
