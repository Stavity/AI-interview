package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.questiontag.model.QuestionSourceType;
import interview.guide.modules.questiontag.service.QuestionGenerationScope;
import interview.guide.modules.questiontag.service.QuestionGenerationScopeFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeTagToolCallbackFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator.TagSelection;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

class VoiceQuestionTaggingServiceTest {

  private final StructuredOutputInvoker structuredOutputInvoker = mock(StructuredOutputInvoker.class);
  private final LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
  private final PromptSanitizer sanitizer = mock(PromptSanitizer.class);
  private final QuestionGenerationScopeFactory scopeFactory = mock(QuestionGenerationScopeFactory.class);
  private final QuestionKnowledgeTagToolCallbackFactory toolFactory =
      mock(QuestionKnowledgeTagToolCallbackFactory.class);
  private final QuestionKnowledgeValidator validator = mock(QuestionKnowledgeValidator.class);
  private final ChatClient client = mock(ChatClient.class);
  private final ToolCallback tool = mock(ToolCallback.class);
  private VoiceQuestionTaggingService service;

  @BeforeEach
  void setUp() {
    service = new VoiceQuestionTaggingService(
        structuredOutputInvoker, providers, sanitizer, scopeFactory, toolFactory, validator);
    when(sanitizer.sanitize(anyString())).thenAnswer(call -> call.getArgument(0));
    when(sanitizer.wrapWithDelimiters(anyString(), anyString()))
        .thenAnswer(call -> call.getArgument(1));
  }

  @Test
  void queriesScopedCatalogAndReturnsOnlyServerValidatedSnapshot() {
    QuestionGenerationScope scope = new QuestionGenerationScope(
        "java-backend", QuestionSourceType.MOCK_INTERVIEW_GENERATED, 7,
        new LinkedHashSet<>(List.of("TECH_KNOWLEDGE.JAVA")), 8);
    List<ToolCallback> tools = List.of(tool);
    when(scopeFactory.current("java-backend", QuestionSourceType.MOCK_INTERVIEW_GENERATED))
        .thenReturn(scope);
    when(toolFactory.create(scope)).thenReturn(tools);
    when(providers.getQuestionGenerationChatClient("dashscope", tools)).thenReturn(client);
    doReturn(new VoiceQuestionTaggingService.TaggingOutput(
        "TECH_KNOWLEDGE.JAVA.CONCURRENCY", List.of("TECH_KNOWLEDGE.JAVA.JMM"),
        4, "SCENARIO"))
        .when(structuredOutputInvoker).invoke(
            eq(client), anyString(), anyString(), any(),
            eq(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED), anyString(), anyString(), any());
    when(validator.validateQuestionGenerationSelection(
        7, scope.allowedRootCodes(), "TECH_KNOWLEDGE.JAVA.CONCURRENCY",
        List.of("TECH_KNOWLEDGE.JAVA.JMM")))
        .thenReturn(new TagSelection(7, "TECH_KNOWLEDGE.JAVA.CONCURRENCY",
            List.of("TECH_KNOWLEDGE.JAVA.JMM")));
    when(validator.requireDifficultyLevel(4)).thenReturn(4);
    when(validator.requireQuestionType("SCENARIO"))
        .thenReturn(interview.guide.modules.questiontag.model.QuestionType.SCENARIO);

    VoiceQuestionTagSnapshot result = service.tagQuestion(
        "dashscope", "java-backend", "请说明一次线上线程池耗尽问题的排查过程。");

    assertThat(result.taxonomyVersion()).isEqualTo(7);
    assertThat(result.primaryKnowledgeCode()).isEqualTo("TECH_KNOWLEDGE.JAVA.CONCURRENCY");
    assertThat(result.secondaryKnowledgeCodes()).containsExactly("TECH_KNOWLEDGE.JAVA.JMM");
    assertThat(result.difficultyLevel()).isEqualTo(4);
    assertThat(result.questionType()).isEqualTo("SCENARIO");
    assertThat(result.taggingStatus()).isEqualTo("VERIFIED");
    verify(toolFactory).create(scope);
    verify(validator).validateQuestionGenerationSelection(
        7, scope.allowedRootCodes(), "TECH_KNOWLEDGE.JAVA.CONCURRENCY",
        List.of("TECH_KNOWLEDGE.JAVA.JMM"));
  }
}
