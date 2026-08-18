package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MultiRoundRoundReportService {
  private final ObjectMapper objectMapper;

  public String build(MultiRoundEntity round, List<MultiRoundQuestionEntity> questions) {
    int answered = (int) questions.stream()
        .filter(question -> question.getStatus() != MultiRoundQuestionEntity.Status.PENDING)
        .count();
    Map<String, Integer> questionTypes = new TreeMap<>();
    questions.forEach(question -> questionTypes.merge(question.getQuestionType(), 1, Integer::sum));
    try {
      return objectMapper.writeValueAsString(new RoundReport(
          round.getRoundNo(), round.getRoundType().name(), questions.size(), answered, questionTypes));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("轮次报告序列化失败", error);
    }
  }

  public record RoundReport(int roundNo, String roundType, int questionCount, int answeredQuestionCount,
                            Map<String, Integer> questionTypes) {}
}
