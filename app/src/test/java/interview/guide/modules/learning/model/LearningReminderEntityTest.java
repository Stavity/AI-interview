package interview.guide.modules.learning.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LearningReminderEntityTest {

  @Test
  @DisplayName("完成任务时取消未投递提醒")
  void shouldCancelPendingReminderWhenTaskCompletes() {
    LearningPlanTaskEntity task = LearningPlanTaskEntity.create("复习 JVM", LocalDateTime.now().toLocalDate());
    LearningReminderEntity reminder = LearningReminderEntity.pending(task, LocalDateTime.now().plusHours(1));

    task.complete();
    reminder.cancelForCompletedTask();

    assertThat(task.getStatus()).isEqualTo(LearningPlanTaskEntity.Status.COMPLETED);
    assertThat(reminder.getStatus()).isEqualTo(LearningReminderEntity.Status.CANCELLED);
  }

  @Test
  @DisplayName("仅到期的待处理提醒进入 READY")
  void shouldOnlyReadyDuePendingReminder() {
    LearningPlanTaskEntity task = LearningPlanTaskEntity.create("复习 JVM", LocalDateTime.now().toLocalDate());
    LearningReminderEntity due = LearningReminderEntity.pending(task, LocalDateTime.now().minusMinutes(1));
    LearningReminderEntity future = LearningReminderEntity.pending(task, LocalDateTime.now().plusMinutes(1));

    assertThat(due.readyIfDue(LocalDateTime.now())).isTrue();
    assertThat(future.readyIfDue(LocalDateTime.now())).isFalse();
    assertThat(due.getStatus()).isEqualTo(LearningReminderEntity.Status.READY);
    assertThat(future.getStatus()).isEqualTo(LearningReminderEntity.Status.PENDING);
  }
}
