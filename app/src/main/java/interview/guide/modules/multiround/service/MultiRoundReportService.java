package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.multiround.repository.MultiRoundLearningAdviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class MultiRoundReportService {
  private final MultiRoundRepository roundRepository; private final MultiRoundLearningAdviceRepository adviceRepository;
  private final ObjectMapper objectMapper;
  @Transactional(readOnly = true)
  public Report report(MultiRoundInterviewEntity interview) {
    var rounds = roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId());
    int completed = (int) rounds.stream().filter(round -> round.getStatus() == MultiRoundEntity.Status.COMPLETED).count();
    var roundReports = rounds.stream()
        .filter(round -> round.getReportJson() != null)
        .map(this::readRoundReport)
        .toList();
    Advice advice = adviceRepository.findByInterviewId(interview.getId()).map(value -> new Advice(value.getSource().name(), value.getAdviceJson())).orElse(null);
    return new Report(interview.getStatus().name(), completed, rounds.size(), completed == rounds.size() ? "全部轮次已完成；学习建议已生成或可恢复生成。" : "面试尚未完成；请按服务端轮次继续。", "DETERMINISTIC", roundReports, advice);
  }
  private MultiRoundRoundReportService.RoundReport readRoundReport(MultiRoundEntity round) {
    try {
      return objectMapper.readValue(round.getReportJson(), MultiRoundRoundReportService.RoundReport.class);
    } catch (Exception error) {
      return new MultiRoundRoundReportService.RoundReport(round.getRoundNo(), round.getRoundType().name(), 0, 0, java.util.Map.of());
    }
  }
  public record Advice(String source, String adviceJson) {}
  public record Report(String status, int completedRounds, int totalRounds, String summary, String source,
                       java.util.List<MultiRoundRoundReportService.RoundReport> rounds, Advice advice) {}
}
