package interview.guide.modules.multiround.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MultiRoundLifecycleService {
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;
  private final MultiRoundRoundReportService roundReportService;

  @Transactional
  public MultiRoundEntity start(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    MultiRoundInterviewEntity interview = round.getInterview();
    if (round.getRoundNo() != interview.getCurrentRoundNo() + 1) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不能跳过未完成的面试轮次");
    }
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    if (round.getStartedAt() == null) round.setStartedAt(LocalDateTime.now());
    interview.setCurrentRoundNo(round.getRoundNo());
    interview.setStatus(MultiRoundInterviewEntity.Status.IN_PROGRESS);
    if (interview.getStartedAt() == null) interview.setStartedAt(LocalDateTime.now());
    return roundRepository.save(round);
  }

  @Transactional
  public MultiRoundEntity finish(Long roundId) {
    MultiRoundEntity round = getRound(roundId);
    if (round.getStatus() == MultiRoundEntity.Status.COMPLETED) return round;
    if (round.getStatus() != MultiRoundEntity.Status.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次尚未启动");
    }
    var questions = questionRepository.findByRoundIdOrderBySequenceNoAsc(roundId);
    if (questions.size() < round.getQuestionLimit() || questions.stream().anyMatch(question -> question.getStatus() == MultiRoundQuestionEntity.Status.PENDING)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前轮次仍有未完成题目");
    }
    round.setStatus(MultiRoundEntity.Status.COMPLETED);
    round.setCompletedAt(LocalDateTime.now());
    round.setReportJson(roundReportService.build(round, questions));
    MultiRoundInterviewEntity interview = round.getInterview();
    if (round.getRoundType() == MultiRoundEntity.Type.HR) {
      interview.setStatus(MultiRoundInterviewEntity.Status.COMPLETED);
      interview.setCompletedAt(LocalDateTime.now());
    } else {
      interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_COMPLETED);
    }
    return roundRepository.save(round);
  }

  private MultiRoundEntity getRound(Long roundId) {
    return roundRepository.findById(roundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "多轮面试轮次不存在: " + roundId));
  }
}
