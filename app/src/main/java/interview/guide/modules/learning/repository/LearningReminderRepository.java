package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningReminderEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningReminderRepository extends JpaRepository<LearningReminderEntity, Long> {
  Page<LearningReminderEntity> findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(
      LearningReminderEntity.Status status, LocalDateTime remindAt, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      update LearningReminderEntity reminder
      set reminder.status = interview.guide.modules.learning.model.LearningReminderEntity.Status.READY
      where reminder.id = :reminderId
        and reminder.status = interview.guide.modules.learning.model.LearningReminderEntity.Status.PENDING
        and reminder.remindAt <= :now
      """)
  int claimDueReminderAsReady(@Param("reminderId") Long reminderId, @Param("now") LocalDateTime now);

  List<LearningReminderEntity> findByTaskAndStatusIn(
      interview.guide.modules.learning.model.LearningPlanTaskEntity task,
      List<LearningReminderEntity.Status> statuses);

  List<LearningReminderEntity> findByStatusOrderByRemindAtAsc(LearningReminderEntity.Status status);
}
