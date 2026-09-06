package interview.guide.common.ai.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.common.ai.agent.AgentRunMetrics;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import interview.guide.modules.multiround.service.AgentRunStepAuditService;
import interview.guide.modules.multiround.service.MultiRoundWorkflowService;
import io.reactivex.rxjava3.core.Flowable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/** Executes one bounded planning run through Google ADK and returns the existing domain plan DTO. */
@Service
@RequiredArgsConstructor
public class AdkInterviewPlanner {
  private static final String EVIDENCE_INSTRUCTION = """
      你是候选人证据分析子 Agent。读取允许的只读工具，归纳既往答案和轮次报告中的强项、薄弱点与不确定性。
      只输出简洁的证据摘要；不得生成题目、修改轮次、写数据库或调用网络。
      """;
  private static final String ADAPTATION_INSTRUCTION = """
      你是面试自适应策略子 Agent。基于本会话中的候选人证据，建议当前轮的覆盖方向和难度倾向。
      只能使用上下文允许的分类；已开始或已完成的题目不可修改。只输出策略建议，不能写数据库或调用网络。
      """;
  private static final String ROOT_INSTRUCTION = """
      你是多轮面试 RootAgent。综合前序子 Agent 的证据与策略，仅为当前轮生成 JSON InterviewPlan。
      不能更改轮次或轮次类型，不能写数据库、调用网络、执行代码或请求其他会话数据。
      仅可使用已提供的只读工具；题目分类必须来自上下文和工具结果。
      """;

  private final LlmProviderRegistry providerRegistry;
  private final PromptSanitizer promptSanitizer;
  private final AdkSessionFactory sessionFactory;
  private final AgentRuntimeProperties runtimeProperties;
  private final AgentRunMetrics metrics;
  private final AgentRunStepAuditService stepAuditService;
  private final MultiRoundWorkflowService workflowService;

  public AdkPlanResult plan(MultiRoundInterviewEntity interview, InterviewPlanningContext context, AgentRunAuditEntity audit) {
    LlmProviderRegistry.OpenAiCompatibleProvider provider = providerRegistry
        .getOpenAiCompatibleProvider(interview.getProviderId());
    if (provider == null || blank(provider.baseUrl()) || blank(provider.apiKey()) || blank(provider.model())) {
      throw new IllegalStateException("当前 Provider 不具备 ADK OpenAI-compatible 配置");
    }
    String safeContext = promptSanitizer.wrapWithDelimiters("adk-interview-context", context.asPrompt());
    String persistedSessionId = workflowService.workflow(interview).getAdkSessionId();
    AdkSessionFactory.SessionHandle session = sessionFactory.createOrResume(
        persistedSessionId == null || persistedSessionId.isBlank() ? "mr-workflow-" + interview.getId() : persistedSessionId,
        interview.getId(), audit.getId(), context.roundNo(), sha256(safeContext),
        runtimeProperties.getPolicyVersion());
    AdkInterviewReadTools readTools = new AdkInterviewReadTools(context,
        new AdkToolPolicy(runtimeProperties.getMaxToolCalls(), metrics),
        new AdkToolAuditSink(stepAuditService, audit));
    BaseAgent agent = rootAgent(provider, readTools);
    Runner runner = Runner.builder().agent(agent).appName(AdkSessionFactory.APP_NAME)
        .sessionService(sessionFactory.sessionService()).build();
    Event event = awaitFinalEvent(runner.runAsync(AdkSessionFactory.USER_ID, session.sessionId(),
        Content.builder().role("user").parts(Part.fromText(safeContext)).build()),
        runtimeProperties.getTimeoutMs());
    String response = event.stringifyContent();
    InterviewPlan plan = new BeanOutputConverter<>(InterviewPlan.class).convert(response);
    return new AdkPlanResult(plan, session.sessionId());
  }

  private BaseAgent rootAgent(LlmProviderRegistry.OpenAiCompatibleProvider provider,
      AdkInterviewReadTools readTools) {
    OpenAiCompatibleAdkModel model = new OpenAiCompatibleAdkModel(provider.model(), provider.baseUrl(),
        provider.apiKey(), runtimeProperties.getTimeoutMs());
    LlmAgent evidenceAgent = LlmAgent.builder().name("evidence_agent")
        .description("Summarizes candidate evidence from persisted interview context")
        .model(model).instruction(EVIDENCE_INSTRUCTION).tools(functionTools(readTools)).maxSteps(3)
        .disallowTransferToParent(true).disallowTransferToPeers(true).build();
    LlmAgent adaptationAgent = LlmAgent.builder().name("adaptation_agent")
        .description("Adjusts future coverage only at the current safe checkpoint")
        .model(model).instruction(ADAPTATION_INSTRUCTION).tools(functionTools(readTools)).maxSteps(3)
        .disallowTransferToParent(true).disallowTransferToPeers(true).build();
    LlmAgent rootAgent = LlmAgent.builder()
        .name("root_interview_agent")
        .description("Produces the bounded plan accepted by the multi-round workflow")
        .model(new OpenAiCompatibleAdkModel(provider.model(), provider.baseUrl(), provider.apiKey(),
            runtimeProperties.getTimeoutMs()))
        .instruction(ROOT_INSTRUCTION + "\n" + new BeanOutputConverter<>(InterviewPlan.class).getFormat())
        .tools(functionTools(readTools))
        .maxSteps(6)
        .disallowTransferToParent(true)
        .disallowTransferToPeers(true)
        .build();
    return SequentialAgent.builder().name("root_interview_workflow")
        .description("Root workflow: evidence, adaptation, then accepted round plan")
        .subAgents(evidenceAgent, adaptationAgent, rootAgent).build();
  }

  private List<FunctionTool> functionTools(AdkInterviewReadTools tools) {
    try {
      return List.of(
          FunctionTool.create(tools, find(tools, "readInterviewSnapshot")),
          FunctionTool.create(tools, find(tools, "readPriorAnswers", int.class)),
          FunctionTool.create(tools, find(tools, "readRoundReports", int.class)),
          FunctionTool.create(tools, find(tools, "readQuestionCoverage")));
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("ADK Tool 注册失败", error);
    }
  }

  private Method find(AdkInterviewReadTools tools, String name, Class<?>... parameters)
      throws ReflectiveOperationException {
    return tools.getClass().getMethod(name, parameters);
  }

  private boolean blank(String value) { return value == null || value.isBlank(); }

  static <T> T awaitFinalEvent(Flowable<T> events, long timeoutMs) {
    if (timeoutMs < 1) throw new IllegalArgumentException("ADK 规划超时必须大于 0");
    return events.timeout(timeoutMs, TimeUnit.MILLISECONDS).blockingLast();
  }

  private String sha256(String text) {
    try {
      byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte value : bytes) result.append(String.format("%02x", value));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public record AdkPlanResult(InterviewPlan plan, String sessionId) {}
}
