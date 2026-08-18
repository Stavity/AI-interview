package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import interview.guide.modules.multiround.repository.AlgorithmSubmissionRepository;
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
}
