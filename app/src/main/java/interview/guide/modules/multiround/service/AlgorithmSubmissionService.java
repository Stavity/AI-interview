package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AlgorithmSubmissionService {
  private static final int MAX_SOURCE_LENGTH = 50_000;
  private final MultiRoundQuestionRepository questionRepository; private final AlgorithmSubmissionRepository submissionRepository;
  @Transactional
  public AlgorithmSubmissionEntity submit(Long questionId, String language, String sourceCode) {
    if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > MAX_SOURCE_LENGTH) throw new BusinessException(ErrorCode.CODE_TOO_LARGE, "代码不能为空且不能超过 50000 字符");
    var question = questionRepository.findById(questionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮题目不存在: " + questionId));
    var submission = submissionRepository.findByQuestionId(questionId).orElseGet(() -> { var created = new AlgorithmSubmissionEntity(); created.setQuestion(question); return created; });
    submission.setLanguage(parseLanguage(language));
    submission.setSourceCode(sourceCode);
    submission.setEvaluationJson("{\"mode\":\"STATIC_REVIEW\",\"notice\":\"AI 静态评审，未执行代码\"}");
    submission.setEvaluationStatus(AlgorithmSubmissionEntity.EvaluationStatus.PENDING);
    submission.setEvaluationErrorCode(null);
    submission.setScore(null);
    return submissionRepository.save(submission);
  }

  /** 原实现直接 Language.valueOf()，非法语言会把内部类名泄露到接口错误信息里。 */
  private AlgorithmSubmissionEntity.Language parseLanguage(String language) {
    try {
      return AlgorithmSubmissionEntity.Language.valueOf(language.trim().toUpperCase());
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的算法语言: " + language);
    }
  }

  @Transactional(readOnly = true)
  public AlgorithmSubmissionEntity get(Long submissionId) {
    return submissionRepository.findById(submissionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "算法提交不存在: " + submissionId));
  }
}
