package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundFinalReportEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundFinalReportRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable final report snapshot. It deliberately contains no model-generated score. */
@Service
@RequiredArgsConstructor
public class MultiRoundFinalReportService {
  private final MultiRoundFinalReportRepository reportRepository;
  private final MultiRoundReportService reportService;
  private final ObjectMapper objectMapper;

  @Transactional
  public void generate(MultiRoundInterviewEntity interview) {
    if (reportRepository.findByInterviewId(interview.getId()).isPresent()) return;
    MultiRoundReportService.Report report = reportService.report(interview);
    MultiRoundFinalReportEntity entity = new MultiRoundFinalReportEntity();
    entity.setInterview(interview); entity.setSource("DETERMINISTIC"); entity.setReportJson(write(report));
    reportRepository.save(entity);
  }

  @Transactional(readOnly = true)
  public MultiRoundReportService.Report find(MultiRoundInterviewEntity interview) {
    return reportRepository.findByInterviewId(interview.getId()).map(entity -> read(entity.getReportJson()))
        .orElseGet(() -> reportService.report(interview));
  }
  private String write(MultiRoundReportService.Report report) {
    try { return objectMapper.writeValueAsString(report); }
    catch (Exception error) { throw new IllegalStateException("无法保存多轮最终报告", error); }
  }
  private MultiRoundReportService.Report read(String json) {
    try { return objectMapper.readValue(json, MultiRoundReportService.Report.class); }
    catch (Exception error) { throw new IllegalStateException("多轮最终报告损坏", error); }
  }
}
