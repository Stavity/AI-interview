package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlgorithmSubmissionReviewPersistenceService {
  private final AlgorithmSubmissionRepository submissionRepository;
  private final ObjectMapper objectMapper;

  @Transactional
  public AlgorithmSubmissionEntity save(Long submissionId, AlgorithmStaticReviewService.Review review) {
    AlgorithmSubmissionEntity submission = submissionRepository.findById(submissionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "算法提交不存在: " + submissionId));
    try {
      AlgorithmStaticReviewService.Review normalized = AlgorithmStaticReviewService.normalize(review);
      submission.setScore(normalized.score());
      submission.setEvaluationJson(objectMapper.writeValueAsString(normalized));
      submission.setEvaluationStatus(AlgorithmSubmissionEntity.EvaluationStatus.COMPLETED);
      submission.setEvaluationErrorCode(null);
      submission.getQuestion().setStatus(interview.guide.modules.multiround.model.MultiRoundQuestionEntity.Status.EVALUATED);
      return submissionRepository.save(submission);
    } catch (Exception error) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "算法静态评审结果序列化失败");
    }
  }

  @Transactional
  public void markPending(Long submissionId) {
    updateStatus(submissionId, AlgorithmSubmissionEntity.EvaluationStatus.PENDING, null);
  }

  @Transactional
  public void markFailed(Long submissionId, String errorCode) {
    updateStatus(submissionId, AlgorithmSubmissionEntity.EvaluationStatus.FAILED, errorCode);
  }

  private void updateStatus(Long submissionId, AlgorithmSubmissionEntity.EvaluationStatus status, String errorCode) {
    AlgorithmSubmissionEntity submission = submissionRepository.findById(submissionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "算法提交不存在: " + submissionId));
    submission.setEvaluationStatus(status);
    submission.setEvaluationErrorCode(errorCode);
    submissionRepository.save(submission);
  }
}
