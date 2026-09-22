package interview.guide.common.ai.adk;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.ai.agent.InterviewPlanningContext;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.service.InitialWorkflowDecision;
import interview.guide.modules.multiround.service.WorkflowMutationDecision;
import interview.guide.modules.multiround.service.FinalLearningAdvice;
import io.reactivex.rxjava3.core.Flowable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/** Bounded ADK sequential workflow: evidence → strategy → root structured plan. */
@Service
@RequiredArgsConstructor
public class AdkInterviewPlanner {
  private static final String EVIDENCE = """
      你是 EvidenceAgent。只使用只读工具归纳既往回答和报告中的优势、薄弱项与不确定性。
      只输出证据摘要；不得出题、写库、修改节点、调用网络或访问其他会话。
      """;
  private static final String STRATEGY = """
      你是 StrategyAgent。根据本会话证据建议当前节点的能力覆盖、难度和追问预算。
      只能使用上下文允许分类；不得写库、修改已开始节点或调用网络。
      """;
  private static final String ROOT = """
      你是 RootAgent。综合 EvidenceAgent 与 StrategyAgent 的结果，只输出当前节点的 JSON InterviewPlan。
      不得改变节点顺序或类型，不得写库、执行代码、调用网络，不能访问其他会话。
      """;
  private static final String INITIAL_ROOT = """
      你是 RootAgent。综合 EvidenceAgent 与 StrategyAgent 的结果，为整场多轮面试输出 JSON InitialWorkflowDecision。
      nodes 必须有 3 到 6 个节点；nodeKey 为小写 kebab-case；nodeType 仅能是 ALGORITHM、TECHNICAL_FOUNDATION、
      PROJECT_DEEP_DIVE、SYSTEM_DESIGN、HR；必须含 ALGORITHM，HR 只能最后；每节点 2 到 4 题、1 到 5 项 coverage，
      总题目数 8 到 16。不得写库、调用网络或改变任何已开始节点。
      """;
  private static final String CHECKPOINT_ROOT = """
      你是 RootAgent。基于刚完成节点的证据，只输出 JSON WorkflowMutationDecision。
      只能为尚未开始的 nodeKey 调整 questionLimit（2-4）、difficultyBand（EASY/MEDIUM/HARD）和 coverage（1-5 项）。
      expectedRevision 必须等于输入的 workflow revision。不得新增、删除、重排节点，不得修改已开始节点或写入任何系统。
      """;
  private static final String FINAL_ROOT = """
      你是 RootAgent。根据已完成面试报告输出 JSON FinalLearningAdvice：包含简明 summary 和 3 到 5 条可执行 recommendations。
      建议必须基于提供的证据，不得更新掌握度、创建学习计划、写数据库或访问网络。
      """;

  private final LlmProviderRegistry providerRegistry;
  private final PromptSanitizer promptSanitizer;
  private final AgentRuntimeProperties properties;
  private final InMemorySessionService sessions = new InMemorySessionService();

  public InterviewPlan plan(MultiRoundInterviewEntity interview, InterviewPlanningContext context) {
    var provider = providerRegistry.getOpenAiCompatibleProvider(interview.getProviderId());
    if (provider == null || blank(provider.baseUrl()) || blank(provider.apiKey()) || blank(provider.model())) {
      throw new IllegalStateException("当前 Provider 不具备 ADK OpenAI-compatible 配置");
    }
    String prompt = promptSanitizer.wrapWithDelimiters("adk-interview-context", context.asPrompt());
    String sessionId = "mr-workflow-" + interview.getId();
    try {
      sessions.createSession("ai-interview", "local-single-user", new java.util.concurrent.ConcurrentHashMap<>(), sessionId)
          .blockingGet();
    } catch (Exception ignored) { }
    Runner runner = Runner.builder().agent(agent(provider, context, ROOT,
        new BeanOutputConverter<>(InterviewPlan.class).getFormat())).appName("ai-interview").sessionService(sessions).build();
    Event event = await(runner.runAsync("local-single-user", sessionId,
        Content.builder().role("user").parts(Part.fromText(prompt)).build()), properties.getTimeoutMs());
    return new BeanOutputConverter<>(InterviewPlan.class).convert(event.stringifyContent());
  }

  /** Uses the same bounded three-agent chain to propose an initial graph before any node starts. */
  public InitialWorkflowDecision planInitialWorkflow(MultiRoundInterviewEntity interview) {
    var provider = providerRegistry.getOpenAiCompatibleProvider(interview.getProviderId());
    if (provider == null || blank(provider.baseUrl()) || blank(provider.apiKey()) || blank(provider.model())) {
      throw new IllegalStateException("当前 Provider 不具备 ADK OpenAI-compatible 配置");
    }
    InterviewPlanningContext context = new InterviewPlanningContext(
        value(interview.getResumeSnapshot()), value(interview.getJdSnapshot()), value(interview.getSkillId()),
        value(interview.getDifficulty()), 0, "WORKFLOW", List.of(), List.of(), List.of(), 4, 2);
    String sessionId = "mr-workflow-" + interview.getId() + "-initial";
    try {
      sessions.createSession("ai-interview", "local-single-user", new java.util.concurrent.ConcurrentHashMap<>(), sessionId)
          .blockingGet();
    } catch (Exception ignored) { }
    Runner runner = Runner.builder().agent(agent(provider, context, INITIAL_ROOT,
        new BeanOutputConverter<>(InitialWorkflowDecision.class).getFormat()))
        .appName("ai-interview").sessionService(sessions).build();
    Event event = await(runner.runAsync("local-single-user", sessionId,
        Content.builder().role("user").parts(Part.fromText(
            promptSanitizer.wrapWithDelimiters("adk-initial-workflow-context", context.asPrompt()))).build()),
        properties.getTimeoutMs());
    return new BeanOutputConverter<>(InitialWorkflowDecision.class).convert(event.stringifyContent());
  }

  /** Proposes a revision-bound patch for future nodes; the caller owns all state checks and writes. */
  public WorkflowMutationDecision planCheckpointMutation(MultiRoundInterviewEntity interview,
      InterviewPlanningContext context, long workflowRevision) {
    var provider = providerRegistry.getOpenAiCompatibleProvider(interview.getProviderId());
    if (provider == null || blank(provider.baseUrl()) || blank(provider.apiKey()) || blank(provider.model())) {
      throw new IllegalStateException("当前 Provider 不具备 ADK OpenAI-compatible 配置");
    }
    String sessionId = "mr-workflow-" + interview.getId() + "-checkpoint-" + workflowRevision;
    try { sessions.createSession("ai-interview", "local-single-user", new java.util.concurrent.ConcurrentHashMap<>(), sessionId).blockingGet(); }
    catch (Exception ignored) { }
    Runner runner = Runner.builder().agent(agent(provider, context, CHECKPOINT_ROOT,
        new BeanOutputConverter<>(WorkflowMutationDecision.class).getFormat()))
        .appName("ai-interview").sessionService(sessions).build();
    String input = "workflowRevision=" + workflowRevision + "\n" + context.asPrompt();
    Event event = await(runner.runAsync("local-single-user", sessionId,
        Content.builder().role("user").parts(Part.fromText(
            promptSanitizer.wrapWithDelimiters("adk-checkpoint-context", input))).build()), properties.getTimeoutMs());
    return new BeanOutputConverter<>(WorkflowMutationDecision.class).convert(event.stringifyContent());
  }

  public FinalLearningAdvice planFinalLearningAdvice(MultiRoundInterviewEntity interview, List<String> reports) {
    InterviewPlanningContext context = new InterviewPlanningContext(value(interview.getResumeSnapshot()), value(interview.getJdSnapshot()),
        value(interview.getSkillId()), value(interview.getDifficulty()), 0, "FINAL_REPORT", List.of(), List.of(), reports, 0, 0);
    return run(interview, context, "final", FINAL_ROOT, new BeanOutputConverter<>(FinalLearningAdvice.class));
  }

  private <T> T run(MultiRoundInterviewEntity interview, InterviewPlanningContext context, String suffix,
      String instruction, BeanOutputConverter<T> converter) {
    var provider = providerRegistry.getOpenAiCompatibleProvider(interview.getProviderId());
    if (provider == null || blank(provider.baseUrl()) || blank(provider.apiKey()) || blank(provider.model())) throw new IllegalStateException("当前 Provider 不具备 ADK OpenAI-compatible 配置");
    String sessionId = "mr-workflow-" + interview.getId() + "-" + suffix;
    try { sessions.createSession("ai-interview", "local-single-user", new java.util.concurrent.ConcurrentHashMap<>(), sessionId).blockingGet(); } catch (Exception ignored) { }
    Runner runner = Runner.builder().agent(agent(provider, context, instruction, converter.getFormat())).appName("ai-interview").sessionService(sessions).build();
    Event event = await(runner.runAsync("local-single-user", sessionId, Content.builder().role("user").parts(Part.fromText(promptSanitizer.wrapWithDelimiters("adk-" + suffix, context.asPrompt()))).build()), properties.getTimeoutMs());
    return converter.convert(event.stringifyContent());
  }

  private BaseAgent agent(LlmProviderRegistry.OpenAiCompatibleProvider provider, InterviewPlanningContext context,
      String rootInstruction, String outputFormat) {
    AdkInterviewReadTools tools = new AdkInterviewReadTools(context, properties.getMaxToolCalls());
    OpenAiCompatibleAdkModel model = new OpenAiCompatibleAdkModel(provider.model(), provider.baseUrl(), provider.apiKey(), properties.getTimeoutMs());
    LlmAgent evidence = LlmAgent.builder().name("evidence_agent").model(model).instruction(EVIDENCE)
        .tools(functionTools(tools)).maxSteps(3).disallowTransferToParent(true).disallowTransferToPeers(true).build();
    LlmAgent strategy = LlmAgent.builder().name("strategy_agent").model(model).instruction(STRATEGY)
        .tools(functionTools(tools)).maxSteps(3).disallowTransferToParent(true).disallowTransferToPeers(true).build();
    LlmAgent root = LlmAgent.builder().name("root_interview_agent").model(model)
        .instruction(rootInstruction + "\n" + outputFormat)
        .tools(functionTools(tools)).maxSteps(6).disallowTransferToParent(true).disallowTransferToPeers(true).build();
    return SequentialAgent.builder().name("root_interview_workflow").subAgents(evidence, strategy, root).build();
  }

  private List<FunctionTool> functionTools(AdkInterviewReadTools tools) {
    try {
      return List.of(FunctionTool.create(tools, method(tools, "readInterviewSnapshot")),
          FunctionTool.create(tools, method(tools, "readPriorAnswers", int.class)),
          FunctionTool.create(tools, method(tools, "readRoundReports", int.class)),
          FunctionTool.create(tools, method(tools, "readQuestionCoverage")));
    } catch (ReflectiveOperationException exception) { throw new IllegalStateException("ADK Tool 注册失败", exception); }
  }
  private Method method(AdkInterviewReadTools tools, String name, Class<?>... parameters) throws ReflectiveOperationException {
    return tools.getClass().getMethod(name, parameters);
  }
  private Event await(Flowable<Event> events, long timeoutMs) {
    return events.timeout(timeoutMs, TimeUnit.MILLISECONDS).blockingLast();
  }
  private boolean blank(String value) { return value == null || value.isBlank(); }
  private String value(String input) { return input == null ? "" : input; }
}
