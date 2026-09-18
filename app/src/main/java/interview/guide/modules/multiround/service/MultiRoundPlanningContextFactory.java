package interview.guide.modules.multiround.service;

import interview.guide.common.ai.agent.AgentBudget;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MultiRoundPlanningContextFactory {
  private static final int ITEM_LIMIT = 1_000;
  private final MultiRoundRepository roundRepository;
  private final MultiRoundQuestionRepository questionRepository;

  public InterviewPlanningContext create(MultiRoundInterviewEntity interview, MultiRoundEntity currentRound) {
    List<String> questions = new ArrayList<>();
    List<String> answers = new ArrayList<>();
    List<String> reports = new ArrayList<>();
    List<MultiRoundEntity> priorRounds = roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId())
        .stream()
        .filter(round -> round.getRoundNo() < currentRound.getRoundNo())
        .toList();
    for (MultiRoundEntity round : priorRounds) {
      if (round.getReportJson() != null && !round.getReportJson().isBlank()) reports.add(limit(round.getReportJson()));
    }
    if (!priorRounds.isEmpty()) {
      // 批量取题目：原实现按轮次逐条查询，造成 1 + N 次查询
      for (MultiRoundQuestionEntity question : questionRepository
          .findByRoundIdInOrderByRoundIdAscSequenceNoAsc(priorRounds.stream().map(MultiRoundEntity::getId).toList())) {
        if (question.getQuestion() != null && !question.getQuestion().isBlank()) questions.add(limit(question.getQuestion()));
        if (question.getAnswer() != null && !question.getAnswer().isBlank()) answers.add(limit(question.getAnswer()));
      }
    }
    InterviewPlanningContext context = new InterviewPlanningContext(
        limit(interview.getResumeSnapshot()), limit(interview.getJdSnapshot()), limit(interview.getSkillId()),
        limit(interview.getDifficulty()), currentRound.getRoundNo(), currentRound.getRoundType().name(),
        List.copyOf(questions), List.copyOf(answers), List.copyOf(reports), currentRound.getQuestionLimit(),
        AgentBudget.DEFAULT.maxFollowUpsPerQuestion());
    if (context.asPrompt().length() > AgentBudget.DEFAULT.maxInputCharacters()) {
      return new InterviewPlanningContext(limit(interview.getResumeSnapshot()), limit(interview.getJdSnapshot()),
          limit(interview.getSkillId()), limit(interview.getDifficulty()), currentRound.getRoundNo(),
          currentRound.getRoundType().name(), List.of(), List.of(), List.of(), currentRound.getQuestionLimit(),
          AgentBudget.DEFAULT.maxFollowUpsPerQuestion());
    }
    return context;
  }

  private String limit(String value) {
    if (value == null) return "";
    return value.length() <= ITEM_LIMIT ? value : value.substring(0, ITEM_LIMIT);
  }
}
