package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MultiRoundOperationService {
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  private final AlgorithmSubmissionRepository submissionRepository;
  private final MultiRoundIdempotencyService idempotencyService;
  private final MultiRoundLifecycleService lifecycleService;
  private final MultiRoundQuestionService questionService;
  private final AlgorithmSubmissionService submissionService;

  @Transactional
  public MultiRoundEntity startRound(Long roundId, String key) {
    MultiRoundEntity round = getRound(roundId);
    if (alreadyHandled(sessionId(round), "START:" + roundId, key)) return round;
    MultiRoundEntity started = lifecycleService.start(roundId);
    idempotencyService.record(sessionId(started), "START:" + roundId, key, started.getInterview(), "ROUND", roundId);
    return started;
  }

  @Transactional
  public MultiRoundEntity finishRound(Long roundId, String key) {
    MultiRoundEntity round = getRound(roundId);
    if (alreadyHandled(sessionId(round), "FINISH:" + roundId, key)) return round;
    MultiRoundEntity finished = lifecycleService.finish(roundId);
    idempotencyService.record(sessionId(finished), "FINISH:" + roundId, key, finished.getInterview(), "ROUND", roundId);
    return finished;
  }

  @Transactional
  public MultiRoundQuestionEntity addQuestion(Long roundId, String key, String type, String category, String text, String reference) {
    MultiRoundEntity round = getRound(roundId);
    String sessionId = sessionId(round);
    requireKey(key);
    var previous = idempotencyService.find(sessionId, "QUESTION:" + roundId, key);
    if (previous.isPresent() && previous.get().getResourceId() != null) {
      return questionRepository.findById(previous.get().getResourceId())
          .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "幂等题目结果不存在"));
    }
    MultiRoundQuestionEntity question = questionService.addQuestion(roundId, type, category, text, reference);
    idempotencyService.record(sessionId, "QUESTION:" + roundId, key, round.getInterview(), "QUESTION", question.getId());
    return question;
  }

  @Transactional
  public void answer(Long questionId, String key, String answer) {
    MultiRoundQuestionEntity question = getQuestion(questionId);
    String sessionId = sessionId(question.getRound());
    if (alreadyHandled(sessionId, "ANSWER:" + questionId, key)) return;
    questionService.answer(questionId, answer);
    idempotencyService.record(sessionId, "ANSWER:" + questionId, key, question.getRound().getInterview(), "QUESTION", questionId);
  }

  @Transactional
  public AlgorithmSubmissionEntity submitAlgorithm(Long questionId, String key, String language, String sourceCode) {
    MultiRoundQuestionEntity question = getQuestion(questionId);
    String sessionId = sessionId(question.getRound());
    requireKey(key);
    var previous = idempotencyService.find(sessionId, "ALGORITHM_SUBMIT:" + questionId, key);
    if (previous.isPresent() && previous.get().getResourceId() != null) {
      return submissionRepository.findById(previous.get().getResourceId())
          .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "幂等提交结果不存在"));
    }
    AlgorithmSubmissionEntity submission = submissionService.submit(questionId, language, sourceCode);
    idempotencyService.record(sessionId, "ALGORITHM_SUBMIT:" + questionId, key, question.getRound().getInterview(), "ALGORITHM_SUBMISSION", submission.getId());
    return submission;
  }

  private boolean alreadyHandled(String sessionId, String operation, String key) {
    requireKey(key);
    return idempotencyService.findCompleted(sessionId, operation, key).isPresent();
  }

  private void requireKey(String key) {
    if (key == null || key.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能为空");
  }

  private MultiRoundEntity getRound(Long roundId) {
    return roundRepository.findById(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
  }

  private MultiRoundQuestionEntity getQuestion(Long questionId) {
    return questionRepository.findById(questionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮题目不存在: " + questionId));
  }

  private String sessionId(MultiRoundEntity round) {
    return round.getInterview().getInterviewSession().getSessionId();
  }
}
