package interview.guide.modules.knowledgebase.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.listener.QuestionGenTaskSubmission;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionTagDTO;
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse;
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig;
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeTagEntity;
import interview.guide.modules.questiontag.model.QuestionSourceRef;
import interview.guide.modules.questiontag.model.QuestionSourceType;
import interview.guide.modules.questiontag.model.QuestionTagRelationType;
import interview.guide.modules.questiontag.model.QuestionTaggingSource;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.model.QuestionType;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeTagRepository;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator;
import interview.guide.modules.questiontag.service.QuestionGenerationScope;
import interview.guide.modules.questiontag.service.QuestionGenerationScopeFactory;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator.TagSelection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionService {

  private static final int DEFAULT_FOLLOW_UP_COUNT = 2;
  private static final int DEFAULT_CATEGORY_LIMIT = 3;
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
  };
  private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
      new TypeReference<>() {
      };

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final ObjectMapper objectMapper;
  private final QuestionGenTaskSubmission questionGenTaskSubmission;
  private final QuestionGenerationStateService questionGenerationStateService;
  private final QuestionKnowledgeTagRepository questionTagRepository;
  private final QuestionKnowledgeNodeRepository knowledgeNodeRepository;
  private final QuestionKnowledgeValidator questionKnowledgeValidator;
  private final QuestionGenerationScopeFactory questionGenerationScopeFactory;

  @Transactional(readOnly = true)
  public List<KnowledgeBaseQuestionDTO> listQuestions(Long knowledgeBaseId,
                                                      KnowledgeBaseQuestionStatus status,
                                                      String category,
                                                      String difficulty,
                                                      String keyword,
                                                      Integer minDifficultyLevel,
                                                      Integer maxDifficultyLevel,
                                                      String questionType,
                                                      String sourceType,
                                                      String taggingStatus,
                                                      Integer taxonomyVersion,
                                                      String knowledgeCode) {
    if (minDifficultyLevel != null && (minDifficultyLevel < 1 || minDifficultyLevel > 5)
        || maxDifficultyLevel != null && (maxDifficultyLevel < 1 || maxDifficultyLevel > 5)
        || minDifficultyLevel != null && maxDifficultyLevel != null
            && minDifficultyLevel > maxDifficultyLevel) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目难度筛选范围必须在 1～5，且下限不高于上限");
    }
    List<KnowledgeBaseQuestionEntity> questions = status == null
        ? questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(knowledgeBaseId)
        : questionRepository.findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(knowledgeBaseId, status);
    String categoryFilter = trimToNull(category);
    String difficultyFilter = trimToNull(difficulty);
    String keywordFilter = trimToNull(keyword);
    String questionTypeFilter = trimToNull(questionType);
    String sourceTypeFilter = trimToNull(sourceType);
    String taggingStatusFilter = trimToNull(taggingStatus);
    String knowledgeCodeFilter = trimToNull(knowledgeCode);
    Map<Long, List<QuestionKnowledgeTagEntity>> tagsByQuestion = questions.isEmpty()
        ? Map.of()
        : questionTagRepository.findByIdQuestionIdIn(
            questions.stream().map(KnowledgeBaseQuestionEntity::getId).toList())
            .stream().collect(Collectors.groupingBy(tag -> tag.getId().getQuestionId()));
    return questions.stream()
        .filter(q -> categoryFilter == null || categoryFilter.equals(q.getCategory()))
        .filter(q -> difficultyFilter == null || difficultyFilter.equals(q.getDifficulty()))
        .filter(q -> minDifficultyLevel == null || q.getDifficultyLevel() != null
            && q.getDifficultyLevel() >= minDifficultyLevel)
        .filter(q -> maxDifficultyLevel == null || q.getDifficultyLevel() != null
            && q.getDifficultyLevel() <= maxDifficultyLevel)
        .filter(q -> questionTypeFilter == null || q.getQuestionType() != null
            && questionTypeFilter.equals(q.getQuestionType().name()))
        .filter(q -> sourceTypeFilter == null || q.getSourceType() != null
            && sourceTypeFilter.equals(q.getSourceType().name()))
        .filter(q -> taggingStatusFilter == null || q.getTaggingStatus() != null
            && taggingStatusFilter.equals(q.getTaggingStatus().name()))
        .filter(q -> taxonomyVersion == null || taxonomyVersion.equals(q.getTaxonomyVersion()))
        .filter(q -> knowledgeCodeFilter == null
            || hasKnowledgeTag(tagsByQuestion.getOrDefault(q.getId(), List.of()), knowledgeCodeFilter))
        .filter(q -> keywordFilter == null || containsKeyword(q, keywordFilter))
        .map(q -> toDTO(q, tagsByQuestion.getOrDefault(q.getId(), List.of())))
        .toList();
  }

  /**
   * 列出某知识库下出现过的方向（含题目计数），按出现频次降序。
   * 用于前端筛选下拉、开始面试弹窗的方向选择。
   */
  @Transactional(readOnly = true)
  public List<CategoryCount> listCategories(Long knowledgeBaseId) {
    return questionRepository.findCategoryCounts(knowledgeBaseId);
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO createQuestion(Long knowledgeBaseId,
                                                 CreateKnowledgeBaseQuestionRequest request) {
    KnowledgeBaseEntity kb = getKnowledgeBase(knowledgeBaseId);
    KnowledgeBaseQuestionEntity question = new KnowledgeBaseQuestionEntity();
    question.setKnowledgeBase(kb);
    applyCreateRequest(question, request);
    question.setKbContentHash(kb.getFileHash());
    question.setSourceRefJson(writeSourceRef(
        new QuestionSourceRef(kb.getId(), kb.getFileHash(), List.of(), kb.getName())));
    question.setStatus(request.status() != null ? request.status() : KnowledgeBaseQuestionStatus.DRAFT);
    TagSelection selection = questionKnowledgeValidator.resolveSelection(
        request.primaryKnowledgeCode(), request.secondaryKnowledgeCodes());
    applyTagSelection(question, selection);
    question = questionRepository.save(question);
    List<QuestionKnowledgeTagEntity> tags = persistTags(question.getId(), selection);
    if (question.getStatus() == KnowledgeBaseQuestionStatus.ACTIVE) {
      questionKnowledgeValidator.validateReadyForActivation(question, tags);
      questionRepository.save(question);
    }
    return toDTO(question, tags);
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO updateQuestion(Long questionId, UpdateKnowledgeBaseQuestionRequest request) {
    KnowledgeBaseQuestionEntity question = getQuestion(questionId);
    boolean taggingMetadataChanged = request.difficultyLevel() != null || request.questionType() != null;
    if (request.difficulty() != null) {
      question.setDifficulty(normalizeDifficulty(request.difficulty()));
    }
    if (request.type() != null) {
      question.setType(trimToNull(request.type()));
    }
    if (request.category() != null) {
      if (request.category().isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "面试方向不能为空");
      }
      question.setCategory(request.category().trim());
    }
    if (request.question() != null) {
      if (request.question().isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "题干不能为空");
      }
      question.setQuestion(request.question().trim());
    }
    if (request.topicSummary() != null) {
      question.setTopicSummary(trimToNull(request.topicSummary()));
    }
    if (request.referenceAnswer() != null) {
      question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
    }
    if (request.keyPoints() != null) {
      question.setKeyPointsJson(writeStringList(request.keyPoints()));
    }
    if (request.scoringRubric() != null) {
      question.setScoringRubric(trimToNull(request.scoringRubric()));
    }
    if (request.followUps() != null) {
      question.setFollowUpsJson(writeFollowUps(request.followUps()));
    }
    if (request.sourceContext() != null) {
      question.setSourceContext(trimToNull(request.sourceContext()));
    }
    if (request.difficultyLevel() != null) {
      question.setDifficultyLevel(validateDifficultyLevel(request.difficultyLevel()));
    }
    if (request.questionType() != null) {
      question.setQuestionType(parseQuestionType(request.questionType()));
    }
    List<QuestionKnowledgeTagEntity> tags = questionTagRepository
        .findByIdQuestionIdOrderByRelationTypeAscIdNodeCodeAsc(questionId);
    if (Boolean.TRUE.equals(request.clearKnowledgeTags())) {
      if (request.primaryKnowledgeCode() != null || request.secondaryKnowledgeCodes() != null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "清除知识点与设置新知识点不能同时提交");
      }
      if (!tags.isEmpty() || question.getTaxonomyVersion() != null
          || question.getTaggingStatus() != QuestionTaggingStatus.REVIEW_REQUIRED) {
        questionTagRepository.deleteByIdQuestionId(questionId);
        tags = List.of();
        question.setTaxonomyVersion(null);
        question.setTaggingStatus(QuestionTaggingStatus.REVIEW_REQUIRED);
        taggingMetadataChanged = true;
      }
    } else if (request.primaryKnowledgeCode() != null || request.secondaryKnowledgeCodes() != null) {
      if (!sameCodes(tags, request.primaryKnowledgeCode(), request.secondaryKnowledgeCodes())) {
        TagSelection selection = questionKnowledgeValidator.resolveSelection(
            request.primaryKnowledgeCode(), request.secondaryKnowledgeCodes());
        questionTagRepository.deleteByIdQuestionId(questionId);
        applyTagSelection(question, selection);
        tags = persistTags(questionId, selection);
        taggingMetadataChanged = true;
      }
    }
    if (request.status() != null) {
      question.setStatus(request.status());
    }
    if (question.getStatus() == KnowledgeBaseQuestionStatus.ACTIVE
        && (request.status() == KnowledgeBaseQuestionStatus.ACTIVE || taggingMetadataChanged)) {
      questionKnowledgeValidator.validateReadyForActivation(question, tags);
    }
    return toDTO(questionRepository.save(question), tags);
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO updateStatus(Long questionId, KnowledgeBaseQuestionStatus status) {
    KnowledgeBaseQuestionEntity question = getQuestion(questionId);
    List<QuestionKnowledgeTagEntity> tags = questionTagRepository
        .findByIdQuestionIdOrderByRelationTypeAscIdNodeCodeAsc(questionId);
    if (status == KnowledgeBaseQuestionStatus.ACTIVE) {
      questionKnowledgeValidator.validateReadyForActivation(question, tags);
    }
    question.setStatus(status);
    return toDTO(questionRepository.save(question), tags);
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteQuestion(Long questionId) {
    if (!questionRepository.existsById(questionId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
    }
    questionRepository.deleteById(questionId);
  }

  /**
   * 提交异步问题生成任务。
   * 校验知识库存在且状态允许，更新状态为 QUEUED，然后向 Kafka 投递任务。
   */
  @Transactional(rollbackFor = Exception.class)
  public QuestionGenStatusResponse submitGenerationTask(
      Long knowledgeBaseId,
      GenerateKnowledgeBaseQuestionsRequest request) {
    String difficulty = normalizeDifficulty(request.difficulty());
    int followUpCount = request.followUpCount() == null
        ? DEFAULT_FOLLOW_UP_COUNT
        : Math.max(0, Math.min(request.followUpCount(), 5));
    int categoryLimit = request.categoryLimit() == null
        ? DEFAULT_CATEGORY_LIMIT
        : Math.max(1, Math.min(request.categoryLimit(), 5));
    QuestionGenerationScope generationScope = questionGenerationScopeFactory.current(
        KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID, QuestionSourceType.KNOWLEDGE_BASE);
    QuestionGenerationConfig config = new QuestionGenerationConfig(
        difficulty,
        Math.max(1, request.questionCount()),
        followUpCount,
        categoryLimit,
        trimToNull(request.llmProvider()),
        generationScope.taxonomyVersion(),
        generationScope.allowedRootCodes().stream().sorted().toList(),
        QuestionGenerationScopeFactory.TAGGING_POLICY_VERSION
    );
    QuestionGenStatusResponse response =
        questionGenerationStateService.createTask(knowledgeBaseId, config);
    boolean sent = questionGenTaskSubmission.sendGenerateTask(
        knowledgeBaseId, response.questionGenTaskId());
    return sent ? response : questionGenerationStateService.getStatus(knowledgeBaseId);
  }

  @Transactional(readOnly = true)
  public QuestionGenStatusResponse getGenerationStatus(Long knowledgeBaseId) {
    return questionGenerationStateService.getStatus(knowledgeBaseId);
  }

  private void applyCreateRequest(KnowledgeBaseQuestionEntity question,
                                  CreateKnowledgeBaseQuestionRequest request) {
    question.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    question.setDifficulty(normalizeDifficulty(request.difficulty()));
    question.setType(trimToNull(request.type()));
    question.setCategory(normalizeCategory(request.category(), null));
    question.setQuestion(request.question().trim());
    question.setTopicSummary(trimToNull(request.topicSummary()));
    question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
    question.setKeyPointsJson(writeStringList(request.keyPoints()));
    question.setScoringRubric(trimToNull(request.scoringRubric()));
    question.setFollowUpsJson(writeFollowUps(request.followUps()));
    question.setSourceContext(trimToNull(request.sourceContext()));
    question.setDifficultyLevel(request.difficultyLevel() == null
        ? null : validateDifficultyLevel(request.difficultyLevel()));
    question.setQuestionType(parseQuestionType(request.questionType()));
    question.setSourceType(QuestionSourceType.MANUAL);
  }

  private KnowledgeBaseQuestionDTO toDTO(KnowledgeBaseQuestionEntity question) {
    return toDTO(question, questionTagRepository
        .findByIdQuestionIdOrderByRelationTypeAscIdNodeCodeAsc(question.getId()));
  }

  private KnowledgeBaseQuestionDTO toDTO(
      KnowledgeBaseQuestionEntity question, List<QuestionKnowledgeTagEntity> tags
  ) {
    // FK 列声明为 insertable=false/updatable=false，save 后不一定回填；
    // 优先取关联实体的 ID（saveAll 后 KnowledgeBase 已在持久态），FK 列只做兜底
    Long knowledgeBaseId = question.getKnowledgeBase() != null && question.getKnowledgeBase().getId() != null
        ? question.getKnowledgeBase().getId()
        : question.getKnowledgeBaseId();
    return new KnowledgeBaseQuestionDTO(
        question.getId(),
        knowledgeBaseId,
        question.getKnowledgeBase() != null ? question.getKnowledgeBase().getName() : null,
        question.getSkillId(),
        question.getDifficulty(),
        question.getType(),
        question.getCategory(),
        question.getQuestion(),
        question.getTopicSummary(),
        question.getReferenceAnswer(),
        readStringList(question.getKeyPointsJson()),
        question.getScoringRubric(),
        readFollowUps(question.getFollowUpsJson()),
        question.getSourceContext(),
        question.getStatus(),
        question.getCreatedAt(),
        question.getUpdatedAt(),
        question.getDifficultyLevel(),
        question.getQuestionType() == null ? null : question.getQuestionType().name(),
        tags.stream()
            .filter(tag -> tag.getRelationType() == QuestionTagRelationType.PRIMARY)
            .map(tag -> tag.getId().getNodeCode())
            .findFirst()
            .orElse(null),
        tags.stream()
            .filter(tag -> tag.getRelationType() == QuestionTagRelationType.SECONDARY)
            .map(tag -> tag.getId().getNodeCode())
            .toList(),
        tags.stream().map(this::toTagDTO).toList(),
        question.getSourceType() == null ? null : question.getSourceType().name(),
        readSourceRef(question.getSourceRefJson()),
        question.getTaxonomyVersion(),
        question.getTaggingStatus() == null ? null : question.getTaggingStatus().name()
    );
  }

  private KnowledgeBaseQuestionTagDTO toTagDTO(QuestionKnowledgeTagEntity tag) {
    String code = tag.getId().getNodeCode();
    String name = knowledgeNodeRepository.findByTaxonomyVersionAndCode(tag.getTaxonomyVersion(), code)
        .map(QuestionKnowledgeNodeEntity::getName)
        .orElse(code);
    return new KnowledgeBaseQuestionTagDTO(
        code, name, tag.getRelationType().name(), tag.getRelevanceWeight(),
        tag.getTaggingSource().name(), tag.getConfidence());
  }

  private boolean hasKnowledgeTag(List<QuestionKnowledgeTagEntity> tags, String codeOrParentCode) {
    return tags.stream()
        .anyMatch(tag -> tag.getId().getNodeCode().equals(codeOrParentCode)
            || tag.getId().getNodeCode().startsWith(codeOrParentCode + "."));
  }

  private void applyTagSelection(KnowledgeBaseQuestionEntity question, TagSelection selection) {
    question.setTaxonomyVersion(selection.taxonomyVersion());
    question.setTaggingStatus(selection.isEmpty()
        ? QuestionTaggingStatus.REVIEW_REQUIRED : QuestionTaggingStatus.VERIFIED);
  }

  private boolean sameCodes(
      List<QuestionKnowledgeTagEntity> existing,
      String requestedPrimary,
      List<String> requestedSecondary
  ) {
    String primaryRequested = trimToNull(requestedPrimary);
    String primary = existing.stream()
        .filter(tag -> tag.getRelationType() == QuestionTagRelationType.PRIMARY)
        .map(tag -> tag.getId().getNodeCode())
        .findFirst()
        .orElse(null);
    java.util.Set<String> secondaries = existing.stream()
        .filter(tag -> tag.getRelationType() == QuestionTagRelationType.SECONDARY)
        .map(tag -> tag.getId().getNodeCode())
        .collect(java.util.stream.Collectors.toSet());
    List<String> requestedSecondaryCodes = requestedSecondary == null ? List.of() : requestedSecondary.stream()
        .filter(code -> code != null && !code.isBlank())
        .map(String::trim)
        .toList();
    java.util.Set<String> requestedSecondarySet = new java.util.HashSet<>(requestedSecondaryCodes);
    return java.util.Objects.equals(primaryRequested, primary)
        && requestedSecondaryCodes.size() == requestedSecondarySet.size()
        && secondaries.equals(requestedSecondarySet);
  }

  private List<QuestionKnowledgeTagEntity> persistTags(Long questionId, TagSelection selection) {
    if (selection.isEmpty()) {
      return List.of();
    }
    List<QuestionKnowledgeTagEntity> tags = new java.util.ArrayList<>();
    tags.add(QuestionKnowledgeTagEntity.create(questionId, selection.taxonomyVersion(),
        selection.primaryCode(), QuestionTagRelationType.PRIMARY, QuestionTaggingSource.MANUAL,
        java.math.BigDecimal.ONE, null));
    selection.secondaryCodes().forEach(code -> tags.add(QuestionKnowledgeTagEntity.create(
        questionId, selection.taxonomyVersion(), code, QuestionTagRelationType.SECONDARY,
        QuestionTaggingSource.MANUAL, java.math.BigDecimal.ONE, null)));
    return questionTagRepository.saveAll(tags);
  }

  private Integer validateDifficultyLevel(Integer difficultyLevel) {
    if (difficultyLevel < 1 || difficultyLevel > 5) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目难度必须为 1～5");
    }
    return difficultyLevel;
  }

  private QuestionType parseQuestionType(String questionType) {
    if (questionType == null || questionType.isBlank()) {
      return null;
    }
    try {
      return QuestionType.valueOf(questionType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的题型: " + questionType);
    }
  }

  private String writeSourceRef(QuestionSourceRef sourceRef) {
    try {
      return objectMapper.writeValueAsString(sourceRef);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目来源信息失败", e);
    }
  }

  private QuestionSourceRef readSourceRef(String sourceRefJson) {
    if (sourceRefJson == null || sourceRefJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(sourceRefJson, QuestionSourceRef.class);
    } catch (JacksonException e) {
      log.warn("解析题目来源信息失败: {}", e.getMessage());
      return null;
    }
  }

  private KnowledgeBaseEntity getKnowledgeBase(Long knowledgeBaseId) {
    return knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
  }

  private KnowledgeBaseQuestionEntity getQuestion(Long questionId) {
    return questionRepository.findById(questionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND));
  }

  private boolean containsKeyword(KnowledgeBaseQuestionEntity question, String keyword) {
    String lower = keyword.toLowerCase(Locale.ROOT);
    return contains(question.getQuestion(), lower)
        || contains(question.getReferenceAnswer(), lower)
        || contains(question.getScoringRubric(), lower)
        || contains(question.getTopicSummary(), lower)
        || contains(question.getCategory(), lower);
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
  }

  private List<String> readStringList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, STRING_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析题目字符串列表失败: {}", e.getMessage());
      return List.of();
    }
  }

  private List<KnowledgeBaseQuestionFollowUpDTO> readFollowUps(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析追问列表失败: {}", e.getMessage());
      return List.of();
    }
  }

  private String writeStringList(List<String> values) {
    try {
      List<String> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && !value.isBlank())
          .map(String::trim)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e);
    }
  }

  private String writeFollowUps(List<KnowledgeBaseQuestionFollowUpDTO> values) {
    try {
      List<KnowledgeBaseQuestionFollowUpDTO> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && value.question() != null && !value.question().isBlank())
          .map(value -> new KnowledgeBaseQuestionFollowUpDTO(
              value.question().trim(),
              trimToNull(value.referenceAnswer()),
              value.keyPoints() == null ? List.of() : value.keyPoints().stream()
                  .filter(item -> item != null && !item.isBlank())
                  .map(String::trim)
                  .toList(),
              trimToNull(value.scoringRubric())
          ))
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
    }
  }

  private String normalizeDifficulty(String difficulty) {
    if (difficulty == null || difficulty.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    return difficulty.trim();
  }

  /**
   * 归一化方向名：模型输出兜底。
   * - 空白时用知识库名（生成场景）或"未分类"（手动创建场景）
   * - 否则去掉首尾空白
   */
  private String normalizeCategory(String category, String fallback) {
    if (category == null || category.isBlank()) {
      return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
    }
    return category.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

}
