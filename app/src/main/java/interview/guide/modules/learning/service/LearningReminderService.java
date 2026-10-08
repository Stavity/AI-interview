package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.learning.model.LearningReminderEntity;
import interview.guide.modules.learning.repository.LearningReminderRepository;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.common.tenant.TenantContext;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class LearningReminderService {

  private static final int SCAN_BATCH_SIZE = 100;

  private final LearningReminderRepository reminderRepository;
  private final UserAccountRepository userRepository;
  private final TransactionTemplate transactionTemplate;

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void scanDueReminders() {
    LocalDateTime now = LocalDateTime.now();
    for (Long ownerUserId : userRepository.findIdsByStatus(UserAccountStatus.ACTIVE)) {
      TenantContext.runAs(ownerUserId, () -> transactionTemplate.executeWithoutResult(status -> {
        int ready = markDueRemindersReady(now);
        if (ready > 0) {
          log.info("当前账户学习提醒已就绪: count={}", ready);
        }
      }));
    }
  }

  @Transactional
  public int markDueRemindersReady(LocalDateTime now) {
    int claimed = 0;
    while (true) {
      var pending = reminderRepository.findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(
          LearningReminderEntity.Status.PENDING, now, PageRequest.of(0, SCAN_BATCH_SIZE));
      if (pending.isEmpty()) {
        return claimed;
      }
      for (LearningReminderEntity reminder : pending) {
        claimed += reminderRepository.claimDueReminderAsReady(reminder.getId(), now);
      }
      if (pending.getNumberOfElements() < SCAN_BATCH_SIZE) {
        return claimed;
      }
    }
  }

  @Transactional
  public void acknowledge(Long reminderId, LearningReminderEntity.Status outcome, String reason) {
    LearningReminderEntity reminder = reminderRepository.findById(reminderId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "学习提醒不存在: " + reminderId));
    if (reminder.acknowledge(outcome, reason)) {
      reminderRepository.save(reminder);
      return;
    }
    if (reminder.getStatus() != outcome) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "学习提醒当前状态不允许投递回执");
    }
  }
}
