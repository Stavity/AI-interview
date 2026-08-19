package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MultiRoundInterviewBootstrapService {
  private final ResumeRepository resumeRepository;
  private final ResumeAnalysisRepository analysisRepository;
  private final InterviewSessionRepository sessionRepository;
  private final MultiRoundInterviewRepository interviewRepository;
  private final MultiRoundInterviewService interviewService;

  private static final List<MultiRoundInterviewEntity.Status> ACTIVE_STATUSES = List.of(
      MultiRoundInterviewEntity.Status.CREATED,
      MultiRoundInterviewEntity.Status.ROUND_READY,
      MultiRoundInterviewEntity.Status.IN_PROGRESS,
      MultiRoundInterviewEntity.Status.ROUND_EVALUATING,
      MultiRoundInterviewEntity.Status.ROUND_COMPLETED);

  @Transactional
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
      return response(active.get(), true);
    }

    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    session.setResume(resume);
    session.setSkillId(request.skillId());
    session.setDifficulty(request.difficulty());
    session.setSourceType("MULTI_ROUND");
    session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
    session.setTotalQuestions(0);
    session.setCurrentQuestionIndex(0);
    session.setQuestionsJson("[]");
    sessionRepository.save(session);

    MultiRoundInterviewEntity interview = interviewService.createUsingDefaultProvider(
        session.getSessionId(), idempotencyKey);
    interview.setInterviewSession(session);
    String snapshot = resume.getResumeText().trim() + "\n\n简历分析摘要:\n" + analysis.getSummary().trim();
    interview.setResumeSnapshot(snapshot);
    interview.setResumeSnapshotVersion(sha256(snapshot));
    interview.setJdSnapshot(normalizedJd);
    interview.setSkillId(request.skillId());
    interview.setDifficulty(request.difficulty());
    interview.setScopeFingerprint(scopeFingerprint);
    interviewRepository.save(interview);
    return response(interview, false);
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
