package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.questiontag.model.QuestionSourceType;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.model.QuestionType;
import interview.guide.modules.questiontag.service.QuestionGenerationScope;
import interview.guide.modules.questiontag.service.QuestionGenerationScopeFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeTagToolCallbackFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator.TagSelection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/** Assigns and validates a frozen taxonomy snapshot for each generated voice question. */
@Service
@Slf4j
public class VoiceQuestionTaggingService {

  private static final String SYSTEM_PROMPT = """
      你负责为一条已经生成的语音面试问题选择知识标签，而不是重新出题。
      - 必须先调用 query_question_knowledge_tags 查询固定目录，不得编造 code。
      - 必要时调用 validate_question_knowledge_tags 检查候选主、次知识点。
      - 只标注最后实际要求候选人回答的问题；开场、反馈和过渡语不属于题目。
      - primaryKnowledgeCode 必须且只能有一个；secondaryKnowledgeCodes 最多 4 个。
      - difficultyLevel 为 1～5；questionType 必须使用给定固定枚举。
      - taxonomyVersion 固定为 %d，允许知识范围为：%s。
      """;

  private final StructuredOutputInvoker structuredOutputInvoker;
  private final LlmProviderRegistry llmProviderRegistry;
  private final PromptSanitizer promptSanitizer;
  private final QuestionGenerationScopeFactory scopeFactory;
  private final QuestionKnowledgeTagToolCallbackFactory tagToolFactory;
  private final QuestionKnowledgeValidator validator;
  private final BeanOutputConverter<TaggingOutput> outputConverter =
      new BeanOutputConverter<>(TaggingOutput.class);

  public VoiceQuestionTaggingService(
      StructuredOutputInvoker structuredOutputInvoker,
      LlmProviderRegistry llmProviderRegistry,
      PromptSanitizer promptSanitizer,
      QuestionGenerationScopeFactory scopeFactory,
      QuestionKnowledgeTagToolCallbackFactory tagToolFactory,
      QuestionKnowledgeValidator validator
  ) {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.llmProviderRegistry = llmProviderRegistry;
    this.promptSanitizer = promptSanitizer;
    this.scopeFactory = scopeFactory;
    this.tagToolFactory = tagToolFactory;
    this.validator = validator;
  }

  public VoiceQuestionTagSnapshot tagQuestion(
      String providerId,
      String skillId,
      String questionText
  ) {
    if (questionText == null || questionText.isBlank()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "语音题目为空，无法标注知识点");
    }
    QuestionGenerationScope scope = scopeFactory.current(
        skillId, QuestionSourceType.MOCK_INTERVIEW_GENERATED);
    BusinessException toolFailure = null;
    try {
      ChatClient toolClient = llmProviderRegistry.getQuestionGenerationChatClient(
          providerId, tagToolFactory.create(scope));
      return validate(invoke(toolClient, scope, questionText, "voice_question_tag_tool"), scope);
    } catch (BusinessException e) {
      toolFailure = e;
      log.warn("语音题目标签工具调用失败，切换为服务端候选集: skill={}, version={}, error={}",
          skillId, scope.taxonomyVersion(), e.getMessage());
    }

    String candidates = scopeFactory.fallbackCandidateSection(
        scope, List.of(skillId, questionText));
    String fallbackSystem = baseSystemPrompt(scope)
        + "\n当前 Provider 未能稳定完成工具调用。只能从以下服务端候选中选择 code，禁止编造：\n"
        + promptSanitizer.wrapWithDelimiters("question_tag_candidates",
            promptSanitizer.sanitize(candidates));
    try {
      ChatClient plainClient = llmProviderRegistry.getPlainChatClient(providerId);
      return validate(invoke(plainClient, fallbackSystem, questionText,
          "voice_question_tag_candidates"), scope);
    } catch (BusinessException fallbackFailure) {
      if (toolFailure != null) {
        fallbackFailure.addSuppressed(toolFailure);
      }
      throw fallbackFailure;
    }
  }

  private TaggingOutput invoke(
      ChatClient client,
      QuestionGenerationScope scope,
      String questionText,
      String context
  ) {
    return invoke(client, baseSystemPrompt(scope), questionText, context);
  }

  private TaggingOutput invoke(
      ChatClient client,
      String systemPrompt,
      String questionText,
      String context
  ) {
    String userPrompt = "请标注这条语音面试问题：\n"
        + promptSanitizer.wrapWithDelimiters("voice_question",
            promptSanitizer.sanitize(questionText));
    return structuredOutputInvoker.invoke(client,
        systemPrompt + "\n" + outputConverter.getFormat(), userPrompt, outputConverter,
        ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "语音题目标签生成失败：", context, log);
  }

  private String baseSystemPrompt(QuestionGenerationScope scope) {
    return SYSTEM_PROMPT.formatted(
        scope.taxonomyVersion(), String.join(", ", scope.allowedRootCodes()));
  }

  private VoiceQuestionTagSnapshot validate(
      TaggingOutput output,
      QuestionGenerationScope scope
  ) {
    if (output == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "语音题目标签结果为空");
    }
    TagSelection selection = validator.validateQuestionGenerationSelection(
        scope.taxonomyVersion(), scope.allowedRootCodes(),
        output.primaryKnowledgeCode(), output.secondaryKnowledgeCodes());
    int difficulty = validator.requireDifficultyLevel(output.difficultyLevel());
    QuestionType questionType = validator.requireQuestionType(output.questionType());
    return new VoiceQuestionTagSnapshot(
        difficulty, questionType.name(), selection.primaryCode(), selection.secondaryCodes(),
        selection.taxonomyVersion(), QuestionTaggingStatus.VERIFIED.name());
  }

  record TaggingOutput(
      String primaryKnowledgeCode,
      List<String> secondaryKnowledgeCodes,
      Integer difficultyLevel,
      String questionType
  ) {}
}
