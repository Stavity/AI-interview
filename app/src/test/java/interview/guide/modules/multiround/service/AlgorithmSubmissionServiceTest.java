package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlgorithmSubmissionServiceTest {
  @Mock private MultiRoundQuestionRepository questionRepository;
  @Mock private AlgorithmSubmissionRepository submissionRepository;

  @Test
  @DisplayName("提交算法代码只保存快照并带未执行代码的静态评审标记")
  void shouldStoreCodeSnapshotWithoutExecution() {
    MultiRoundQuestionEntity question = algorithmQuestion(4L);
    when(questionRepository.findById(4L)).thenReturn(Optional.of(question));
    when(submissionRepository.findByQuestionId(4L)).thenReturn(Optional.empty());
    when(submissionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AlgorithmSubmissionEntity submission = new AlgorithmSubmissionService(questionRepository, submissionRepository)
        .submit(4L, "JAVA", "class Solution {} ");

    assertThat(submission.getLanguage()).isEqualTo(AlgorithmSubmissionEntity.Language.JAVA);
    assertThat(submission.getSourceCode()).isEqualTo("class Solution {} ");
    assertThat(submission.getEvaluationJson()).contains("未执行代码");
    assertThat(question.getStatus()).isEqualTo(MultiRoundQuestionEntity.Status.ANSWERED);
  }

  @Test
  @DisplayName("同一算法题再次提交时覆盖快照并重置评审状态")
  void shouldUpdateExistingSubmissionForQuestion() {
    MultiRoundQuestionEntity question = algorithmQuestion(4L);
    AlgorithmSubmissionEntity existing = new AlgorithmSubmissionEntity(); existing.setQuestion(question);
    existing.setSourceCode("old"); existing.setEvaluationStatus(AlgorithmSubmissionEntity.EvaluationStatus.COMPLETED); existing.setScore(88);
    when(questionRepository.findById(4L)).thenReturn(Optional.of(question));
    when(submissionRepository.findByQuestionId(4L)).thenReturn(Optional.of(existing));
    when(submissionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AlgorithmSubmissionEntity submission = new AlgorithmSubmissionService(questionRepository, submissionRepository)
        .submit(4L, "PYTHON", "def solve(): pass");

    assertThat(submission).isSameAs(existing);
    assertThat(submission.getLanguage()).isEqualTo(AlgorithmSubmissionEntity.Language.PYTHON);
    assertThat(submission.getEvaluationStatus()).isEqualTo(AlgorithmSubmissionEntity.EvaluationStatus.PENDING);
    assertThat(submission.getScore()).isNull();
  }

  private MultiRoundQuestionEntity algorithmQuestion(Long id) {
    MultiRoundEntity round = new MultiRoundEntity();
    round.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    question.setId(id);
    question.setQuestionType("ALGORITHM");
    question.setRound(round);
    return question;
  }
}
