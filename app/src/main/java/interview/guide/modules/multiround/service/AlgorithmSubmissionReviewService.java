package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlgorithmSubmissionReviewService {
  private final AlgorithmSubmissionRepository submissionRepository;
  private final AlgorithmStaticReviewService staticReviewService;
  private final AlgorithmSubmissionReviewPersistenceService persistenceService;

  public AlgorithmStaticReviewService.Review review(Long submissionId) {
    Snapshot snapshot = loadSnapshot(submissionId);
    persistenceService.markPending(submissionId);
    try {
      AlgorithmStaticReviewService.Review review = staticReviewService.review(snapshot.providerId(), snapshot.language(), snapshot.question(), snapshot.sourceCode());
      persistenceService.save(submissionId, review);
      return review;
    } catch (RuntimeException error) {
      persistenceService.markFailed(submissionId, error instanceof BusinessException business ? "BUSINESS_" + business.getCode() : "AI_SERVICE_ERROR");
      throw error;
    }
  }

  @Transactional(readOnly = true)
  public Snapshot loadSnapshot(Long submissionId) {
    var submission = submissionRepository.findForReviewById(submissionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "算法提交不存在: " + submissionId));
    String providerId = submission.getQuestion().getRound().getInterview().getProviderId();
    if (providerId == null || providerId.isBlank()) throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "多轮面试未绑定可用 Provider");
    return new Snapshot(providerId, submission.getLanguage().name(), submission.getQuestion().getQuestion(), submission.getSourceCode());
  }

  public record Snapshot(String providerId, String language, String question, String sourceCode) {}
}
