package interview.guide.modules.multiround.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.adk.AdkInterviewPlanner;
import interview.guide.common.ai.adk.AgentRuntimeProperties;
import interview.guide.modules.multiround.model.*;
import interview.guide.modules.multiround.repository.*;
import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.List;
import lombok.RequiredArgsConstructor; import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service @Slf4j @RequiredArgsConstructor
public class MultiRoundLearningAdviceService {
  private final MultiRoundLearningAdviceRepository adviceRepository; private final MultiRoundRepository roundRepository;
  private final AdkInterviewPlanner planner; private final AgentRuntimeProperties properties; private final ObjectMapper objectMapper;
  private final AdkWorkflowExecutionService executionService;
  public void generateIfCompleted(MultiRoundInterviewEntity interview) {
    if (interview.getStatus() != MultiRoundInterviewEntity.Status.COMPLETED || adviceRepository.findByInterviewId(interview.getId()).isPresent()) return;
    List<String> reports = roundRepository.findByInterviewIdOrderByRoundNoAsc(interview.getId()).stream().map(MultiRoundEntity::getReportJson).filter(v -> v != null && !v.isBlank()).toList();
    FinalLearningAdvice advice; MultiRoundLearningAdviceEntity.Source source;
    try { if (properties.getRuntime() != AgentRuntimeProperties.Runtime.GOOGLE_ADK) throw new IllegalStateException("ADK disabled"); advice = executionService.execute(interview, "FINAL_LEARNING_ADVICE", "reports=" + reports.size(), () -> planner.planFinalLearningAdvice(interview, reports)); source = MultiRoundLearningAdviceEntity.Source.GOOGLE_ADK; }
    catch (Exception error) { log.info("最终学习建议使用确定性降级: interviewId={}", interview.getId()); advice = new FinalLearningAdvice("面试已完成，建议优先复盘未覆盖或回答不充分的能力项。", List.of("复盘每轮报告中的薄弱项", "针对岗位核心技能完成一次项目复盘", "一周后进行针对性模拟面试")); source = MultiRoundLearningAdviceEntity.Source.DETERMINISTIC; }
    try { MultiRoundLearningAdviceEntity entity = new MultiRoundLearningAdviceEntity(); entity.setInterview(interview); entity.setSource(source); entity.setInputHash(hash(String.join("\n", reports))); entity.setAdviceJson(objectMapper.writeValueAsString(advice)); adviceRepository.save(entity); }
    catch (Exception error) { throw new IllegalStateException("学习建议投影无法持久化", error); }
  }
  private String hash(String value) { try { byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format("%02x",x)); return b.toString(); } catch(Exception e){throw new IllegalStateException(e);} }
}
