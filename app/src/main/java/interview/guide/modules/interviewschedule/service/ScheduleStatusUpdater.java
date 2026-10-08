package interview.guide.modules.interviewschedule.service;

import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.auth.model.UserAccountStatus;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    private final InterviewScheduleRepository repository;
    private final UserAccountRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "0 0 * * * ?")
    public void updateExpiredInterviews() {
        LocalDateTime now = LocalDateTime.now();
        for (Long ownerUserId : userRepository.findIdsByStatus(UserAccountStatus.ACTIVE)) {
            TenantContext.runAs(ownerUserId, () -> transactionTemplate.executeWithoutResult(status -> {
                int updated = repository.updateStatusByStatusAndInterviewTimeBefore(
                    InterviewStatus.CANCELLED, InterviewStatus.PENDING, now);
                if (updated > 0) {
                    log.info("已将 {} 条当前账户的过期面试标记为已取消", updated);
                }
            }));
        }
    }
}
