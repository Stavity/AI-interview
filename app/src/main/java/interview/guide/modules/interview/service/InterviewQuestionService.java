package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.questiontag.model.QuestionSourceRef;
import interview.guide.modules.questiontag.model.QuestionSourceRef.Generator;
import interview.guide.modules.questiontag.model.QuestionSourceType;
import interview.guide.modules.questiontag.model.QuestionType;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.service.QuestionGenerationScope;
import interview.guide.modules.questiontag.service.QuestionGenerationScopeFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeTagToolCallbackFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator.TagSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
    private static final int MAX_FOLLOW_UP_COUNT = 2;
    private static final double RESUME_QUESTION_RATIO = 0.6;

    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
        "junior", "校招/0-1年经验。考察基础概念和简单应用。",
        "mid", "1-3年经验。考察原理理解和实战经验。",
        "senior", "3年+经验。考察架构设计和深度调优。"
    );

    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final QuestionGenerationScopeFactory scopeFactory;
    private final QuestionKnowledgeTagToolCallbackFactory tagToolFactory;
    private final QuestionKnowledgeValidator questionKnowledgeValidator;
    private final ExecutorService questionExecutor;
    private final int followUpCount;

    private record QuestionListDTO(List<QuestionDTO> questions) {}

    private record QuestionDTO(
        String question,
        String type,
        String category,
        String topicSummary,
        List<String> followUps,
        String primaryKnowledgeCode,
        List<String> secondaryKnowledgeCodes,
        Integer difficultyLevel,
        String questionType
    ) {}

    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer,
            QuestionGenerationScopeFactory scopeFactory,
            QuestionKnowledgeTagToolCallbackFactory tagToolFactory,
            QuestionKnowledgeValidator questionKnowledgeValidator) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.scopeFactory = scopeFactory;
        this.tagToolFactory = tagToolFactory;
        this.questionKnowledgeValidator = questionKnowledgeValidator;
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
    }

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    @PreDestroy
    void destroy() {
        questionExecutor.shutdownNow();
    }

    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String llmProvider,
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText) {

        if (questionCount < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "出题数量必须至少为 1");
        }

        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String difficultyDesc = resolveDifficulty(difficulty);
        QuestionGenerationScope scope = scopeFactory.current(
            skill.id(), QuestionSourceType.MOCK_INTERVIEW_GENERATED);
        List<ToolCallback> tagTools = tagToolFactory.create(scope);
        ChatClient configuredQuestionClient;
        boolean configuredToolCalling;
        try {
            configuredQuestionClient = llmProviderRegistry.getQuestionGenerationChatClient(llmProvider, tagTools);
            configuredToolCalling = true;
        } catch (BusinessException e) {
            log.warn("当前环境未启用题目标签工具调用，将使用服务端候选标签降级: {}", e.getMessage());
            configuredQuestionClient = llmProviderRegistry.getPlainChatClient(llmProvider);
            configuredToolCalling = false;
        }
        final ChatClient questionChatClient = configuredQuestionClient;
        final boolean toolCallingEnabled = configuredToolCalling;
        String providerId = llmProviderRegistry.canonicalProviderId(llmProvider);
        QuestionSourceRef sourceRef = new QuestionSourceRef(null, null, List.of(),
            "mock interview question generation",
            new Generator(providerId, llmProviderRegistry.configuredChatModel(providerId), "question-generation-v1"));

        boolean hasResume = resumeText != null && !resumeText.isBlank();
        String historicalSection = buildHistoricalSection(historicalQuestions);
        List<String> fallbackTagQueries = buildFallbackTagQueries(skill, resumeText);
        if (!hasResume) {
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection, scope, sourceRef, toolCallingEnabled, fallbackTagQueries);
        }

        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        int directionCount = questionCount - resumeCount;

        log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount);

        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
            () -> generateResumeQuestions(questionChatClient, resumeText, resumeCount, skill,
                difficultyDesc, historicalSection, scope, sourceRef, toolCallingEnabled, fallbackTagQueries),
            questionExecutor);

        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
            () -> generateDirectionOnly(questionChatClient, skill, difficultyDesc, directionCount,
                historicalSection, scope, sourceRef, toolCallingEnabled, fallbackTagQueries),
            questionExecutor);

        List<InterviewQuestionDTO> resumeQuestions;
        List<InterviewQuestionDTO> directionQuestions;
        try {
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            log.error("简历题生成失败，降级为全方向题", e.getCause());
            directionFuture.cancel(true);
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection, scope, sourceRef, toolCallingEnabled, fallbackTagQueries);
        }

        try {
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            log.error("方向题生成失败，降级为全简历题", e.getCause());
            if (resumeQuestions.isEmpty()) {
                throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "方向题生成失败，且简历题未生成可用结果");
            }
            return generateResumeQuestions(questionChatClient, resumeText, questionCount, skill,
                difficultyDesc, historicalSection, scope, sourceRef, toolCallingEnabled, fallbackTagQueries);
        }

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            log.warn("简历题和方向题均为空，回退到默认问题");
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "题目标签校验失败，无法安全生成面试题");
        }

        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        log.info("并行出题成功: 简历题={}, 方向题={}, 合计={}",
            resumeQuestions.size(), directionQuestions.size(), merged.size());
        return merged;
    }

    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient, String resumeText, int questionCount,
            SkillDTO skill, String difficultyDesc, String historicalSection,
            QuestionGenerationScope scope, QuestionSourceRef sourceRef,
            boolean toolCallingEnabled, List<String> fallbackTagQueries) {
        if (questionCount <= 0) {
            return List.of();
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("resumeText", resumeText);
            variables.put("historicalSection", historicalSection);

            String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + buildTaggingInstruction(scope);
            String userPrompt = resumeUserPromptTemplate.render(variables);

            List<InterviewQuestionDTO> questions = invokeAndValidateQuestionList(
                questionClient, systemPrompt, userPrompt, scope, sourceRef,
                toolCallingEnabled, fallbackTagQueries, questionCount, "简历题");
            log.info("简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历题生成异常: {}", e.getMessage(), e);
            throw e;
        }
    }

    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient, SkillDTO skill, String difficultyDesc,
            int questionCount, String historicalSection,
            QuestionGenerationScope scope, QuestionSourceRef sourceRef,
            boolean toolCallingEnabled, List<String> fallbackTagQueries) {
        if (questionCount <= 0) {
            return List.of();
        }
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());

        log.info("方向题生成: skill={}, total={}, allocation={}",
            skill.id(), questionCount, allocation);

        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("allocationTable", allocationTable);
            variables.put("historicalSection", historicalSection);
            variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
            variables.put("jdSection", buildJdSection(skill.sourceJd()));

            String systemPrompt = skillSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + GENERIC_MODE_SYSTEM_APPEND
                + buildTaggingInstruction(scope);
            String userPrompt = skillUserPromptTemplate.render(variables);

            List<InterviewQuestionDTO> questions = invokeAndValidateQuestionList(
                questionClient, systemPrompt, userPrompt, scope, sourceRef,
                toolCallingEnabled, fallbackTagQueries, questionCount, "方向题");
            log.info("方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("方向题生成失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：" + e.getMessage());
        }
    }

    private List<InterviewQuestionDTO> mergeQuestionBatches(
            List<InterviewQuestionDTO> first, List<InterviewQuestionDTO> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        int offset = first.size();
        List<InterviewQuestionDTO> merged = new ArrayList<>(first);
        for (InterviewQuestionDTO q : second) {
            int newIndex = q.questionIndex() + offset;
            Integer newParent = q.parentQuestionIndex() != null
                ? q.parentQuestionIndex() + offset : null;
            merged.add(q.withQuestionIndex(newIndex, newParent));
        }
        return merged;
    }

    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
            DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
    }

    private List<InterviewQuestionDTO> invokeAndValidateQuestionList(
            ChatClient questionClient,
            String baseSystemPrompt,
            String userPrompt,
            QuestionGenerationScope scope,
            QuestionSourceRef sourceRef,
            boolean toolCallingEnabled,
            List<String> fallbackTagQueries,
            int expectedCount,
            String context
    ) {
        BusinessException lastValidationError = null;
        if (toolCallingEnabled) {
            for (int attempt = 0; attempt < 2; attempt++) {
                String systemPrompt = baseSystemPrompt
                    + (lastValidationError == null ? "" : buildValidationFeedback(lastValidationError))
                    + "\n\n" + outputConverter.getFormat();
                QuestionListDTO dto;
                try {
                    dto = structuredOutputInvoker.invoke(questionClient, systemPrompt, userPrompt,
                        outputConverter, ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                        context + "生成失败：", context, log);
                } catch (BusinessException e) {
                    lastValidationError = e;
                    break;
                }
                try {
                    return toValidatedQuestions(dto, scope, sourceRef, expectedCount, context);
                } catch (BusinessException e) {
                    lastValidationError = e;
                }
            }
        }

        ChatClient fallbackClient = toolCallingEnabled
            ? llmProviderRegistry.getPlainChatClient(sourceRef.generator().providerId())
            : questionClient;
        String candidateSection = scopeFactory.fallbackCandidateSection(scope, fallbackTagQueries);
        String fallbackSystemPrompt = baseSystemPrompt
            + "\n\n# 标签候选降级模式\n"
            + "当前 Provider 未能完成稳定的 Tool Calling。不要调用工具；只允许从下面服务端按本次固定版本和范围检索得到的候选知识点中选择 code。"
            + "如果候选标签不足以支持合法输出，不要编造 code；本次生成将失败且不会保存无标签题目。\n"
            + promptSanitizer.wrapWithDelimiters("question_tag_candidates",
                promptSanitizer.sanitize(candidateSection))
            + (lastValidationError == null ? "" : buildValidationFeedback(lastValidationError))
            + "\n\n" + outputConverter.getFormat();
        log.warn("题目标签工具调用降级为服务端候选集: skill={}, version={}, reason={}",
            scope.skillId(), scope.taxonomyVersion(),
            lastValidationError == null ? "ToolCallingManager unavailable" : lastValidationError.getMessage());
        QuestionListDTO fallbackDto = structuredOutputInvoker.invoke(fallbackClient,
            fallbackSystemPrompt, userPrompt, outputConverter,
            ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
            context + "候选标签降级生成失败：", context + "_candidate_fallback", log);
        return toValidatedQuestions(fallbackDto, scope, sourceRef, expectedCount, context);
    }

    private List<InterviewQuestionDTO> toValidatedQuestions(
            QuestionListDTO dto, QuestionGenerationScope scope, QuestionSourceRef sourceRef,
            int expectedCount, String context) {
        List<InterviewQuestionDTO> questions = capToMainCount(
            convertToQuestions(dto, scope, sourceRef), expectedCount);
        requireQuestionCount(questions, expectedCount, context);
        return questions;
    }

    private String buildValidationFeedback(BusinessException error) {
        return "\n\n# 后端标签校验反馈\n上一版输出未通过校验，请修正标签、难度、题型或题目数量后重新生成。\n"
            + promptSanitizer.wrapWithDelimiters("question_validation_error",
                promptSanitizer.sanitize(error.getMessage()));
    }

    private List<String> buildFallbackTagQueries(SkillDTO skill, String sourceText) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (skill != null) {
            addSearchTerm(terms, skill.name());
            if (skill.categories() != null) {
                skill.categories().forEach(category -> {
                    addSearchTerm(terms, category.label());
                    addSearchTerm(terms, category.key());
                });
            }
        }
        if (sourceText != null && !sourceText.isBlank()) {
            for (String part : sourceText.split("[\\r\\n，。；：！？、]+")) {
                addSearchTerm(terms, part);
                if (terms.size() >= 10) {
                    break;
                }
            }
        }
        return terms.stream().limit(10).toList();
    }

    private void addSearchTerm(LinkedHashSet<String> terms, String value) {
        if (value == null || value.isBlank() || terms.size() >= 10) {
            return;
        }
        String term = value.trim();
        terms.add(term.length() > 100 ? term.substring(0, 100) : term);
    }

    private List<InterviewQuestionDTO> convertToQuestions(
            QuestionListDTO dto, QuestionGenerationScope scope, QuestionSourceRef sourceRef) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO q : dto.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            String type = (q.type() != null && !q.type().isBlank())
                ? q.type().toUpperCase() : DEFAULT_QUESTION_TYPE;
            TagSelection selection = questionKnowledgeValidator.validateQuestionGenerationSelection(
                scope.taxonomyVersion(), scope.allowedRootCodes(), q.primaryKnowledgeCode(),
                q.secondaryKnowledgeCodes());
            int level = questionKnowledgeValidator.requireDifficultyLevel(q.difficultyLevel());
            QuestionType questionType = questionKnowledgeValidator.requireQuestionType(q.questionType());
            int mainQuestionIndex = index;
            questions.add(InterviewQuestionDTO.create(index++, q.question(), type, q.category(),
                q.topicSummary(), false, null).withTagMetadata(level, questionType.name(),
                selection.primaryCode(), selection.secondaryCodes(), scope.sourceType().name(), sourceRef,
                selection.taxonomyVersion(), QuestionTaggingStatus.VERIFIED.name()));

            List<String> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, followUps.get(i), type,
                    buildFollowUpCategory(q.category(), i + 1), null, true, mainQuestionIndex
                ).withTagMetadata(level, questionType.name(), selection.primaryCode(),
                    selection.secondaryCodes(), scope.sourceType().name(), sourceRef,
                    selection.taxonomyVersion(), QuestionTaggingStatus.VERIFIED.name()));
            }
        }

        return questions;
    }

    private String buildTaggingInstruction(QuestionGenerationScope scope) {
        return """

            # 题目知识标签（必须遵守）
            - 本次 taxonomyVersion 固定为 %d，允许知识范围为：%s。
            - 先使用 query_question_knowledge_tags 查询目录，不得自行编造或拼接知识点 code；必要时使用 validate_question_knowledge_tags 校验。
            - 每个主问题都必须输出 primaryKnowledgeCode、secondaryKnowledgeCodes（最多 4 个）、difficultyLevel（1～5）和 questionType。
            - questionType 只能是 CONCEPT、PRINCIPLE、COMPARISON、CODING、SQL、DESIGN、SCENARIO、DEBUGGING、CODE_REVIEW、PROJECT、OPERATION、BEHAVIORAL。
            - 难度 1～2 为基础，3 为中等，4～5 为高阶；追问继承主问题的标签。
            - 如果目录确实没有合适知识点，仍需按最接近的合法知识点输出；后端会再次严格校验。
            """.formatted(scope.taxonomyVersion(), String.join(", ", scope.allowedRootCodes()));
    }

    private void requireQuestionCount(List<InterviewQuestionDTO> questions, int expected, String context) {
        long actual = questions.stream().filter(question -> !question.isFollowUp()).count();
        if (actual != expected) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                context + "数量不符合要求：请求 " + expected + "，实际 " + actual);
        }
    }

    /**
     * 将问题列表截断到指定的主问题数量（AI 多生时截断，少生时保留原样并记录警告）。
     */
    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions, int maxMainCount) {
        long currentMainCount = questions.stream().filter(q -> !q.isFollowUp()).count();

        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
            }
            return questions;
        }

        List<InterviewQuestionDTO> capped = new ArrayList<>();
        int mainSeen = 0;
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp()) {
                mainSeen++;
            }
            if (mainSeen > maxMainCount) {
                break;
            }
            capped.add(q);
        }
        log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount);
        return capped;
    }

    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "暂无历史提问";
        }

        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion hq : historicalQuestions) {
            String type = hq.type() != null && !hq.type().isBlank() ? hq.type() : DEFAULT_QUESTION_TYPE;
            String summary = hq.topicSummary();
            if (summary == null || summary.isBlank()) {
                String q = hq.question();
                summary = q.length() > 30 ? q.substring(0, 30) + "…" : q;
            }
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(summary);
        }

        StringBuilder sb = new StringBuilder("已考过的知识点（避免重复出题）：\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# Skill Persona\n"
            + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
            + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
    }

    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(followUpCount)
            .collect(Collectors.toList());
    }

    private String buildFollowUpCategory(String category, int order) {
        String base = (category == null || category.isBlank()) ? "追问" : category;
        return base + "（追问" + order + "）";
    }

}
