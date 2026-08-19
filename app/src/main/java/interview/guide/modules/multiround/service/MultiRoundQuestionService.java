package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class MultiRoundQuestionService {
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  @Transactional
  public MultiRoundQuestionEntity addQuestion(Long roundId, String type, String category, String text, String reference) {
    MultiRoundEntity round = roundRepository.findById(roundId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
    int next = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId).size() + 1;
    if (next > round.getQuestionLimit()) throw new BusinessException(ErrorCode.BAD_REQUEST, "本轮题目数量已达到上限");
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次尚未启动");
    }
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity(); question.setRound(round); question.setSequenceNo(next); question.setQuestionType(type); question.setCategoryKey(category); question.setQuestion(text); question.setReferenceAnswer(reference); return questionRepository.save(question);
  }
  @Transactional
  public void answer(Long questionId, String answer) {
    MultiRoundQuestionEntity question = questionRepository.findById(questionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮题目不存在: " + questionId));
    if (question.getStatus() != MultiRoundQuestionEntity.Status.PENDING) return;
    MultiRoundEntity round = question.getRound();
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.ROUND_STATE_INVALID, "题目所属轮次不在进行中");
    }
    question.setAnswer(answer); question.setStatus(MultiRoundQuestionEntity.Status.ANSWERED); questionRepository.save(question);
  }
}
