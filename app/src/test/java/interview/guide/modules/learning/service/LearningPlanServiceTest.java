package interview.guide.modules.learning.service;

import interview.guide.modules.learning.model.LearningPlanEntity;
import interview.guide.modules.learning.model.LearningPlanTaskEntity;
import interview.guide.modules.learning.model.LearningReminderEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningPlanRepository;
import interview.guide.modules.learning.repository.LearningPlanTaskRepository;
import interview.guide.modules.learning.repository.LearningReminderRepository;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningPlanServiceTest {
  @Mock private LearningPlanRepository planRepository;
  @Mock private LearningPlanTaskRepository taskRepository;
  @Mock private LearningReminderRepository reminderRepository;
  @Mock private LearningSkillSourceRepository skillSourceRepository;
  @Mock private LearningProgressService progressService;

  private LearningPlanService service() {
    LearningProperties learningProperties = new LearningProperties();
    learningProperties.setTaskCompletionDelta(5);
    return new LearningPlanService(planRepository, taskRepository, reminderRepository, skillSourceRepository,
        progressService, learningProperties);
  }

  @Test
  @DisplayName("创建计划时保持 DRAFT 状态")
  void shouldCreateDraftPlan() {
    when(planRepository.save(any(LearningPlanEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    LearningPlanEntity plan = service()
        .createDraft("Java 冲刺", "补齐 JVM", LocalDate.now(), LocalDate.now().plusDays(7));
    assertThat(plan.getStatus()).isEqualTo(LearningPlanEntity.Status.DRAFT);
    verify(planRepository).save(plan);
  }

  @Test
  @DisplayName("确认草稿计划后才可创建学习任务")
  void shouldRequireActivePlanBeforeCreatingTask() {
    LearningPlanEntity plan = new LearningPlanEntity();
    plan.setStartDate(LocalDate.of(2026, 8, 1));
    plan.setEndDate(LocalDate.of(2026, 8, 31));
    plan.setStatus(LearningPlanEntity.Status.DRAFT);
    when(planRepository.findById(2L)).thenReturn(Optional.of(plan));

    LearningPlanService service = service();
    assertThatThrownBy(() -> service.createTask(2L, "复习 JVM", LocalDate.of(2026, 8, 20), 45, null, null))
        .hasMessageContaining("请先确认学习计划");

    when(planRepository.save(any(LearningPlanEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    service.activate(2L);
    assertThat(plan.getStatus()).isEqualTo(LearningPlanEntity.Status.ACTIVE);
  }

  @Test
  @DisplayName("完成任务会取消任务的待处理提醒")
  void shouldCompleteTaskAndCancelPendingReminders() {
    LearningPlanTaskEntity task = LearningPlanTaskEntity.create("JVM", LocalDate.now());
    LearningReminderEntity reminder = LearningReminderEntity.pending(task, LocalDateTime.now().plusHours(1));
    when(taskRepository.findById(8L)).thenReturn(Optional.of(task));
    when(reminderRepository.findByTaskAndStatusIn(any(), any())).thenReturn(java.util.List.of(reminder));
    when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    LearningSkillSourceEntity source = new LearningSkillSourceEntity();
    task.setSkillSource(source);
    service().completeTask(8L);

    assertThat(task.getStatus()).isEqualTo(LearningPlanTaskEntity.Status.COMPLETED);
    assertThat(reminder.getStatus()).isEqualTo(LearningReminderEntity.Status.CANCELLED);
    verify(reminderRepository).saveAll(java.util.List.of(reminder));
    service().completeTask(8L);
    verify(progressService, times(1)).adjustMastery(source, 5);
  }

  @Test
  @DisplayName("创建学习任务时会关联计划、可选技能来源和提醒")
  void shouldCreateTaskWithOptionalSourceAndReminder() {
    LearningPlanEntity plan = new LearningPlanEntity();
    plan.setStartDate(LocalDate.of(2026, 8, 1));
    plan.setEndDate(LocalDate.of(2026, 8, 31));
    plan.setStatus(LearningPlanEntity.Status.ACTIVE);
    LearningSkillSourceEntity source = new LearningSkillSourceEntity();
    when(planRepository.findById(2L)).thenReturn(Optional.of(plan));
    when(skillSourceRepository.findById(9L)).thenReturn(Optional.of(source));
    when(taskRepository.save(any(LearningPlanTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(reminderRepository.save(any(LearningReminderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    LearningPlanTaskEntity task = service()
        .createTask(2L, "复习 JVM 内存模型", LocalDate.of(2026, 8, 20), 45, 9L,
            LocalDateTime.of(2026, 8, 19, 20, 0));

    assertThat(task.getPlan()).isSameAs(plan);
    assertThat(task.getSkillSource()).isSameAs(source);
    assertThat(task.getEstimatedMinutes()).isEqualTo(45);
    verify(reminderRepository).save(any(LearningReminderEntity.class));
  }
}
