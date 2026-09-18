package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多轮面试启动的持久化部分。
 * <p>
 * 单独成 bean 是为了让 {@link MultiRoundInterviewBootstrapService} 能在
 * <b>事务边界之外</b> 捕获唯一索引冲突（uq_multi_round_active_scope）：
 * 冲突发生后 PostgreSQL 事务已进入 aborted 状态，同一事务内无法再执行任何查询来回读，
 * 必须等事务回滚结束才能重新读取。沿用本模块 AlgorithmSubmissionReviewPersistenceService
 * 的拆分惯例。
 * <p>
 * 只接收标量参数：跨事务传递游离实体会带来 LazyInitializationException 与
 * detached instance 风险，故这里按 id 重新加载。
 */
@Service
@RequiredArgsConstructor
public class MultiRoundBootstrapPersistenceService {

  private final ResumeRepository resumeRepository;
  private final InterviewSessionRepository sessionRepository;
  private final MultiRoundInterviewRepository interviewRepository;
  private final MultiRoundInterviewService interviewService;

  /** 会话与多轮面试必须在同一事务内创建，避免出现有会话无面试的半成品状态。 */
  @Transactional
  public MultiRoundInterviewEntity create(
      Long resumeId, String skillId, String difficulty, String idempotencyKey,
      String normalizedJd, String scopeFingerprint, String resumeSnapshot, String snapshotVersion) {
    var resume = resumeRepository.findById(resumeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在: " + resumeId));

    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    session.setResume(resume);
    session.setSkillId(skillId);
    session.setDifficulty(difficulty);
    session.setSourceType("MULTI_ROUND");
    session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
    session.setTotalQuestions(0);
    session.setCurrentQuestionIndex(0);
    session.setQuestionsJson("[]");
    sessionRepository.save(session);

    MultiRoundInterviewEntity interview =
        interviewService.createUsingDefaultProvider(session.getSessionId(), idempotencyKey);
    interview.setInterviewSession(session);
    interview.setResumeSnapshot(resumeSnapshot);
    interview.setResumeSnapshotVersion(snapshotVersion);
    interview.setJdSnapshot(normalizedJd);
    interview.setSkillId(skillId);
    interview.setDifficulty(difficulty);
    interview.setScopeFingerprint(scopeFingerprint);
    return interviewRepository.save(interview);
  }
}
