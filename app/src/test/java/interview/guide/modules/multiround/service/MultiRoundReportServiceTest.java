package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.multiround.repository.MultiRoundLearningAdviceRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundReportServiceTest {
  @Mock private MultiRoundRepository roundRepository;
  @Mock private MultiRoundLearningAdviceRepository adviceRepository;
  @Test @DisplayName("报告汇总已完成轮次和总轮次数")
  void shouldSummarizeRoundProgress() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setId(5L); interview.setStatus(MultiRoundInterviewEntity.Status.ROUND_COMPLETED);
    MultiRoundEntity first = new MultiRoundEntity(); first.setStatus(MultiRoundEntity.Status.COMPLETED);
    MultiRoundEntity second = new MultiRoundEntity(); second.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    when(roundRepository.findByInterviewIdOrderByRoundNoAsc(5L)).thenReturn(List.of(first, second));
    when(adviceRepository.findByInterviewId(5L)).thenReturn(java.util.Optional.empty());
    MultiRoundReportService.Report report = new MultiRoundReportService(roundRepository, adviceRepository, new ObjectMapper()).report(interview);
    assertThat(report.completedRounds()).isEqualTo(1); assertThat(report.totalRounds()).isEqualTo(2); assertThat(report.status()).isEqualTo("ROUND_COMPLETED");
  }
}
