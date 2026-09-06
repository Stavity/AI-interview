package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlgorithmSubmissionReviewPersistenceServiceTest {
  @Mock private AlgorithmSubmissionRepository submissionRepository;

  @Test
  @DisplayName("评审失败会保留代码快照并持久化失败状态和错误码")
  void shouldPersistReviewFailureState() {
    AlgorithmSubmissionEntity submission = new AlgorithmSubmissionEntity();
    when(submissionRepository.findById(8L)).thenReturn(Optional.of(submission));
    when(submissionRepository.save(any(AlgorithmSubmissionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    AlgorithmSubmissionReviewPersistenceService service = new AlgorithmSubmissionReviewPersistenceService(submissionRepository, new ObjectMapper());
    service.markFailed(8L, "AI_SERVICE_ERROR");

    assertThat(submission.getEvaluationStatus()).isEqualTo(AlgorithmSubmissionEntity.EvaluationStatus.FAILED);
    assertThat(submission.getEvaluationErrorCode()).isEqualTo("AI_SERVICE_ERROR");
  }

  @Test
  @DisplayName("评审成功后将对应算法题标记为已评审")
  void shouldMarkQuestionEvaluatedWhenReviewSucceeds() {
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    question.setStatus(MultiRoundQuestionEntity.Status.ANSWERED);
    AlgorithmSubmissionEntity submission = new AlgorithmSubmissionEntity();
    submission.setQuestion(question);
    when(submissionRepository.findById(9L)).thenReturn(Optional.of(submission));
    when(submissionRepository.save(any(AlgorithmSubmissionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    new AlgorithmSubmissionReviewPersistenceService(submissionRepository, new ObjectMapper()).save(9L,
        new AlgorithmStaticReviewService.Review(80, false, false, "O(n)", "O(n)", "prefix sum", "O(n)", "O(n)", List.of(), List.of("边界错误"), List.of(), List.of()));

    assertThat(question.getStatus()).isEqualTo(MultiRoundQuestionEntity.Status.EVALUATED);
  }

  @Test
  @DisplayName("正确性与要求复杂度均通过时无条件记为满分")
  void shouldAwardFullScoreWhenCorrectnessAndRequiredComplexityPass() {
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity();
    AlgorithmSubmissionEntity submission = new AlgorithmSubmissionEntity();
    submission.setQuestion(question);
    when(submissionRepository.findById(10L)).thenReturn(Optional.of(submission));
    when(submissionRepository.save(any(AlgorithmSubmissionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    new AlgorithmSubmissionReviewPersistenceService(submissionRepository, new ObjectMapper()).save(10L,
        new AlgorithmStaticReviewService.Review(72, true, true, "O(n)", "O(n)", "哈希表", "O(n)", "O(n)", List.of(), List.of(), List.of("命名可更清晰"), List.of()));

    assertThat(submission.getScore()).isEqualTo(100);
    assertThat(submission.getEvaluationJson()).contains("\"score\":100");
  }
}
