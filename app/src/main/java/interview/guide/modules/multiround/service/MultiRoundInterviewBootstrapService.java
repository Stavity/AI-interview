package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.multiround.dto.MultiRoundInterviewBootstrapResponse;
import interview.guide.modules.multiround.dto.StartMultiRoundInterviewRequest;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundInterviewRepository;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MultiRoundInterviewBootstrapService {
  private static final long LOCK_WAIT_SECONDS = 20;
  private static final long LOCK_LEASE_SECONDS = 120;

  private final ResumeRepository resumeRepository;
  private final ResumeAnalysisRepository analysisRepository;
  private final MultiRoundInterviewRepository interviewRepository;
  private final MultiRoundBootstrapPersistenceService persistenceService;
  private final RedisService redisService;
  private final MultiRoundAutomaticStartService automaticStartService;

  private static final List<MultiRoundInterviewEntity.Status> ACTIVE_STATUSES = List.of(
      MultiRoundInterviewEntity.Status.CREATED,
      MultiRoundInterviewEntity.Status.ROUND_READY,
      MultiRoundInterviewEntity.Status.IN_PROGRESS,
      MultiRoundInterviewEntity.Status.ROUND_EVALUATING,
      MultiRoundInterviewEntity.Status.ROUND_COMPLETED);

  /**
   * 启动多轮面试。同一 scopeFingerprint 的并发请求由分布式锁串行化，
   * 锁失效时由 uq_multi_round_active_scope 唯一索引兜底——此时在事务外回读活跃面试，
   * 返回 resumed 响应而非 500。
   * <p>
   * 本方法不带事务：持久化在 {@link MultiRoundBootstrapPersistenceService} 的独立事务内完成，
   * 否则唯一索引冲突会污染当前事务，导致无法在同一事务内回读。
   */
  public MultiRoundInterviewBootstrapResponse start(
      Long resumeId, StartMultiRoundInterviewRequest request, String idempotencyKey) {
    var resume = resumeRepository.findById(resumeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在: " + resumeId));
    if (resume.getAnalyzeStatus() != AsyncTaskStatus.COMPLETED) {
      throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND, "简历尚未解析完成");
    }
    if (isBlank(resume.getResumeText())) {
      throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND, "简历解析文本不存在");
    }
    var analysis = analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId);
    if (analysis == null || isBlank(analysis.getSummary())) {
      throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND, "简历分析结果不存在");
    }

    String normalizedJd = normalize(request.jdText());
    String scopeFingerprint = sha256(resumeId + "\n" + request.skillId() + "\n"
        + request.difficulty() + "\n" + normalizedJd);
    var active = interviewRepository.findFirstByScopeFingerprintAndStatusIn(
        scopeFingerprint, ACTIVE_STATUSES);
    if (active.isPresent()) {
      automaticStartService.ensureFirstNodeStarted(active.get());
      return response(active.get(), true);
    }

    String snapshot = resume.getResumeText().trim() + "\n\n简历分析摘要:\n" + analysis.getSummary().trim();
    return redisService.executeWithLock(
        "multiround:bootstrap:" + scopeFingerprint,
        LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS,
        () -> {
          var underLock = interviewRepository.findFirstByScopeFingerprintAndStatusIn(
              scopeFingerprint, ACTIVE_STATUSES);
          if (underLock.isPresent()) {
            automaticStartService.ensureFirstNodeStarted(underLock.get());
            return response(underLock.get(), true);
          }
          try {
            MultiRoundInterviewEntity created = persistenceService.create(
                resumeId, request.skillId(), request.difficulty(), idempotencyKey,
                normalizedJd, scopeFingerprint, snapshot, sha256(snapshot));
            automaticStartService.ensureFirstNodeStarted(created);
            return response(created, false);
          } catch (DataIntegrityViolationException conflict) {
            log.info("多轮面试范围指纹冲突，回读既有活跃面试: resumeId={}", resumeId);
            return interviewRepository
                .findFirstByScopeFingerprintAndStatusIn(scopeFingerprint, ACTIVE_STATUSES)
                .map(existing -> {
                  automaticStartService.ensureFirstNodeStarted(existing);
                  return response(existing, true);
                })
                .orElseThrow(() -> new BusinessException(
                    ErrorCode.MULTI_ROUND_OPERATION_IN_PROGRESS, "多轮面试正在创建中，请稍后重试"));
          }
        });
  }

  @Transactional(readOnly = true)
  public Optional<MultiRoundInterviewBootstrapResponse> findActive(Long resumeId) {
    return interviewRepository.findFirstByInterviewSessionResumeIdAndStatusInOrderByUpdatedAtDesc(
        resumeId, ACTIVE_STATUSES).map(interview -> response(interview, true));
  }

  private MultiRoundInterviewBootstrapResponse response(
      MultiRoundInterviewEntity interview, boolean resumed) {
    return new MultiRoundInterviewBootstrapResponse(
        interview.getInterviewSession().getSessionId(),
        interview.getId(),
        interview.getStatus().name(),
        interview.getCurrentRoundNo(),
        resumed);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte item : digest) result.append(String.format("%02x", item));
      return result.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("无法计算多轮面试范围指纹", exception);
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
