package interview.guide.modules.learning.service;

import interview.guide.modules.learning.model.LearningPlanTaskEntity;
import interview.guide.modules.learning.model.LearningReminderEntity;
import interview.guide.modules.learning.repository.LearningReminderRepository;
import org.springframework.data.domain.PageImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningReminderServiceTest {

  @Mock
  private LearningReminderRepository reminderRepository;

  @Test
  @DisplayName("扫描到期提醒时通过条件更新抢占 READY，避免多实例重复投递")
  void shouldClaimDuePendingRemindersWithConditionalUpdate() {
    LearningPlanTaskEntity task = LearningPlanTaskEntity.create("JVM", LocalDateTime.now().toLocalDate());
    LearningReminderEntity reminder = LearningReminderEntity.pending(task, LocalDateTime.now().minusMinutes(1));
    when(reminderRepository.findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(reminder)));
    when(reminderRepository.claimDueReminderAsReady(eq(reminder.getId()), any())).thenReturn(1);

    int readyCount = new LearningReminderService(reminderRepository).markDueRemindersReady(LocalDateTime.now());

    assertThat(readyCount).isOne();
    verify(reminderRepository).claimDueReminderAsReady(eq(reminder.getId()), any());
  }

  @Test
  @DisplayName("浏览器投递成功后将 READY 提醒标记为已送达且重复回执幂等")
  void shouldAcknowledgeDeliveredReminderIdempotently() {
    LearningPlanTaskEntity task = LearningPlanTaskEntity.create("JVM", LocalDateTime.now().toLocalDate());
    LearningReminderEntity reminder = LearningReminderEntity.pending(task, LocalDateTime.now().minusMinutes(1));
    reminder.readyIfDue(LocalDateTime.now());
    when(reminderRepository.findById(8L)).thenReturn(Optional.of(reminder));
    when(reminderRepository.save(any(LearningReminderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    LearningReminderService service = new LearningReminderService(reminderRepository);
    service.acknowledge(8L, LearningReminderEntity.Status.DELIVERED, null);
    service.acknowledge(8L, LearningReminderEntity.Status.DELIVERED, null);

    assertThat(reminder.getStatus()).isEqualTo(LearningReminderEntity.Status.DELIVERED);
    assertThat(reminder.getDeliveredAt()).isNotNull();
    verify(reminderRepository).save(reminder);
  }
}
