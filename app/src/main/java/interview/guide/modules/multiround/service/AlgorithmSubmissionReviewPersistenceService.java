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
      submission.setScore(Math.max(0, Math.min(100, review.score())));
      submission.setEvaluationJson(objectMapper.writeValueAsString(review));
      submission.setEvaluationStatus(AlgorithmSubmissionEntity.EvaluationStatus.COMPLETED);
      submission.setEvaluationErrorCode(null);
      return submissionRepository.save(submission);
    } catch (Exception error) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "算法静态评审结果序列化失败");
    }
  }

  /**
   * 置为待评审，并清除上一次评审的分数与结果。
   * <p>
   * 原实现只改状态：对已 COMPLETED 的提交重新评审且本次失败时，
   * 状态变 FAILED 而 score / evaluationJson 仍是上次成功的值，
   * 界面会显示「失败」却带着旧分数。
   */
  @Transactional
  public void markPending(Long submissionId) {
    AlgorithmSubmissionEntity submission = loadSubmission(submissionId);
    submission.setEvaluationStatus(AlgorithmSubmissionEntity.EvaluationStatus.PENDING);
    submission.setEvaluationErrorCode(null);
    submission.setScore(null);
    submission.setEvaluationJson(null);
    submissionRepository.save(submission);
  }

  @Transactional
  public void markFailed(Long submissionId, String errorCode) {
    updateStatus(submissionId, AlgorithmSubmissionEntity.EvaluationStatus.FAILED, errorCode);
  }

  private void updateStatus(Long submissionId, AlgorithmSubmissionEntity.EvaluationStatus status, String errorCode) {
    AlgorithmSubmissionEntity submission = loadSubmission(submissionId);
    submission.setEvaluationStatus(status);
    submission.setEvaluationErrorCode(truncateErrorCode(errorCode));
    submissionRepository.save(submission);
  }

  private AlgorithmSubmissionEntity loadSubmission(Long submissionId) {
    return submissionRepository.findById(submissionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "算法提交不存在: " + submissionId));
  }

  /** evaluation_error_code 为 VARCHAR(64)，错误码是动态拼接的，需截断避免写入时越界。 */
  private String truncateErrorCode(String errorCode) {
    if (errorCode == null) {
      return null;
    }
    return errorCode.length() <= 64 ? errorCode : errorCode.substring(0, 64);
  }
}
