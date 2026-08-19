package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundPlanningContextFactoryTest {
  @Mock private MultiRoundRepository roundRepository;
  @Mock private MultiRoundQuestionRepository questionRepository;

  @Test
  void shouldBuildContextFromSnapshotsAndThisInterviewPriorAnswers() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(10L);
    interview.setResumeSnapshot("Candidate project: cache migration");
    interview.setJdSnapshot("Java distributed systems");
    interview.setSkillId("java-backend");
    interview.setDifficulty("mid");
    MultiRoundEntity previous = new MultiRoundEntity();
    previous.setId(11L);
    previous.setRoundNo(1);
    previous.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    previous.setReportJson("{\"summary\":\"algorithm complete\"}");
    MultiRoundQuestionEntity priorQuestion = new MultiRoundQuestionEntity();
    priorQuestion.setQuestion("Explain the trade-off");
    priorQuestion.setAnswer("answer from round one");
    MultiRoundEntity current = new MultiRoundEntity();
    current.setId(12L);
    current.setInterview(interview);
    current.setRoundNo(2);
    current.setRoundType(MultiRoundEntity.Type.TECHNICAL_1);
    current.setQuestionLimit(2);
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(10L)).thenReturn(List.of(previous, current));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(11L)).thenReturn(List.of(priorQuestion));

    InterviewPlanningContext context = new MultiRoundPlanningContextFactory(roundRepository, questionRepository)
        .create(interview, current);

    assertThat(context.resumeSnapshot()).contains("cache migration");
    assertThat(context.jdSnapshot()).contains("distributed systems");
    assertThat(context.previousAnswers()).containsExactly("answer from round one");
    assertThat(context.priorQuestionSummaries()).containsExactly("Explain the trade-off");
    assertThat(context.priorRoundReportSummaries()).containsExactly("{\"summary\":\"algorithm complete\"}");
  }
}
