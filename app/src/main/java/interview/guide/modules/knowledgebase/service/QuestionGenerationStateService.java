package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse;
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.questiontag.model.QuestionKnowledgeTagEntity;
import interview.guide.modules.questiontag.model.QuestionTagRelationType;
import interview.guide.modules.questiontag.model.QuestionTaggingSource;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator.TagSelection;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeTagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库题目生成状态的事务边界。
 */
@Service
public class QuestionGenerationStateService {

  public static final String SAFE_FAILURE_MESSAGE = "题目生成失败，请稍后重试";

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final QuestionKnowledgeTagRepository questionTagRepository;
  private final ObjectMapper objectMapper;

  @Autowired
  public QuestionGenerationStateService(
      KnowledgeBaseRepository knowledgeBaseRepository,
      KnowledgeBaseQuestionRepository questionRepository,
      QuestionKnowledgeTagRepository questionTagRepository,
      ObjectMapper objectMapper
  ) {
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.questionRepository = questionRepository;
    this.questionTagRepository = questionTagRepository;
    this.objectMapper = objectMapper;
  }

  // Retained only for same-package legacy unit tests; production writes must provide validated tags.
  QuestionGenerationStateService(KnowledgeBaseRepository knowledgeBaseRepository,
                                 KnowledgeBaseQuestionRepository questionRepository,
                                 ObjectMapper objectMapper) {
    this(knowledgeBaseRepository, questionRepository, null, objectMapper);
  }

  @Transactional(rollbackFor = Exception.class)
  public QuestionGenStatusResponse createTask(
      Long knowledgeBaseId,
      QuestionGenerationConfig config
  ) {
    KnowledgeBaseEntity kb = lockKnowledgeBase(knowledgeBaseId);
    if (kb.getVectorStatus() != VectorStatus.COMPLETED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库尚未完成向量化");
    }
    if (isActive(kb.getQuestionGenStatus())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库问题正在生成中，请勿重复提交");
    }

    LocalDateTime now = LocalDateTime.now();
    kb.setQuestionGenTaskId(UUID.randomUUID().toString());
    kb.setQuestionGenStatus(QuestionGenStatus.QUEUED);
    kb.setQuestionGenConfig(writeConfig(config));
    kb.setQuestionGenError(null);
    kb.setQuestionGenMessage(null);
    kb.setQuestionGenSavedCount(0);
    kb.setQuestionGenSkippedCount(0);
    kb.setQuestionGenUpdatedAt(now);
    knowledgeBaseRepository.save(kb);
    return toResponse(kb, config);
  }

  @Transactional(readOnly = true)
  public QuestionGenStatusResponse getStatus(Long knowledgeBaseId) {
    KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    return toResponse(kb, readConfigOrNull(kb.getQuestionGenConfig()));
  }

  @Transactional(readOnly = true)
  public QuestionGenerationConfig getConfig(Long knowledgeBaseId, String taskId) {
    KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    if (!taskId.equals(kb.getQuestionGenTaskId())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目生成任务已失效");
    }
    QuestionGenerationConfig config = readConfigOrNull(kb.getQuestionGenConfig());
    if (config == null) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目生成配置不存在");
    }
    return config;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean tryMarkProcessing(Long knowledgeBaseId, String taskId) {
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (!matches(kb, taskId, QuestionGenStatus.QUEUED)) {
      return false;
    }
    kb.setQuestionGenStatus(QuestionGenStatus.PROCESSING);
    kb.setQuestionGenError(null);
    kb.setQuestionGenUpdatedAt(LocalDateTime.now());
    return true;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean resetForRetry(Long knowledgeBaseId, String taskId) {
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
      return false;
    }
    kb.setQuestionGenStatus(QuestionGenStatus.QUEUED);
    kb.setQuestionGenUpdatedAt(LocalDateTime.now());
    return true;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean markFailed(Long knowledgeBaseId, String taskId) {
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (kb == null || !taskId.equals(kb.getQuestionGenTaskId())) {
      return false;
    }
    if (kb.getQuestionGenStatus() == QuestionGenStatus.COMPLETED) {
      return false;
    }
    kb.setQuestionGenStatus(QuestionGenStatus.FAILED);
    kb.setQuestionGenError(SAFE_FAILURE_MESSAGE);
    kb.setQuestionGenUpdatedAt(LocalDateTime.now());
    return true;
  }

  @Transactional(rollbackFor = Exception.class)
  boolean replaceQuestionsAndComplete(
      Long knowledgeBaseId, String taskId, List<KnowledgeBaseQuestionEntity> questions, int skippedCount
  ) {
    if (questionTagRepository != null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目生成必须同时提交后端校验的知识标签");
    }
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
      return false;
    }
    questions.forEach(question -> question.setKnowledgeBase(kb));
    questionRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
    questionRepository.saveAll(questions);
    completeTask(kb, questions.size(), skippedCount);
    return true;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean replaceQuestionsAndComplete(
      Long knowledgeBaseId,
      String taskId,
      List<KnowledgeBaseQuestionEntity> questions,
      List<TagSelection> tagSelections,
      int skippedCount
  ) {
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
      return false;
    }
    if (tagSelections == null || questions.size() != tagSelections.size()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "生成题目与标签校验结果数量不一致");
    }

    questions.forEach(question -> question.setKnowledgeBase(kb));
    questionRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
    List<KnowledgeBaseQuestionEntity> savedQuestions = questionRepository.saveAllAndFlush(questions);
    List<QuestionKnowledgeTagEntity> tags = new ArrayList<>();
    for (int index = 0; index < savedQuestions.size(); index++) {
      KnowledgeBaseQuestionEntity question = savedQuestions.get(index);
      TagSelection selection = tagSelections.get(index);
      question.setTaxonomyVersion(selection.taxonomyVersion());
      question.setTaggingStatus(QuestionTaggingStatus.VERIFIED);
      tags.add(QuestionKnowledgeTagEntity.create(question.getId(), selection.taxonomyVersion(),
          selection.primaryCode(), QuestionTagRelationType.PRIMARY, QuestionTaggingSource.LLM,
          BigDecimal.ONE, null));
      selection.secondaryCodes().forEach(code -> tags.add(QuestionKnowledgeTagEntity.create(
          question.getId(), selection.taxonomyVersion(), code, QuestionTagRelationType.SECONDARY,
          QuestionTaggingSource.LLM, new BigDecimal("0.5000"), null)));
    }
    questionTagRepository.saveAll(tags);

    int savedCount = questions.size();
    completeTask(kb, savedCount, skippedCount);
    return true;
  }

  private void completeTask(KnowledgeBaseEntity kb, int savedCount, int skippedCount) {
    String message = skippedCount > 0
        ? String.format("已生成 %d 道题，跳过 %d 道重复题", savedCount, skippedCount)
        : String.format("已生成 %d 道题", savedCount);
    kb.setQuestionGenStatus(QuestionGenStatus.COMPLETED);
    kb.setQuestionGenError(null);
    kb.setQuestionGenMessage(message);
    kb.setQuestionGenSavedCount(savedCount);
    kb.setQuestionGenSkippedCount(skippedCount);
    kb.setQuestionGenUpdatedAt(LocalDateTime.now());
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean touchQueuedForRecovery(
      Long knowledgeBaseId,
      String taskId,
      LocalDateTime threshold
  ) {
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (!matches(kb, taskId, QuestionGenStatus.QUEUED)
        || !isStale(kb.getQuestionGenUpdatedAt(), threshold)) {
      return false;
    }
    kb.setQuestionGenUpdatedAt(LocalDateTime.now());
    return true;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean resetStaleProcessing(
      Long knowledgeBaseId,
      String taskId,
      LocalDateTime threshold
  ) {
    KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
    if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)
        || !isStale(kb.getQuestionGenUpdatedAt(), threshold)) {
      return false;
    }
    kb.setQuestionGenStatus(QuestionGenStatus.QUEUED);
    kb.setQuestionGenUpdatedAt(LocalDateTime.now());
    return true;
  }

  private KnowledgeBaseEntity lockKnowledgeBase(Long knowledgeBaseId) {
    return knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
  }

  private KnowledgeBaseEntity lockKnowledgeBaseOrNull(Long knowledgeBaseId) {
    return knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId).orElse(null);
  }

  private boolean matches(
      KnowledgeBaseEntity kb,
      String taskId,
      QuestionGenStatus expectedStatus
  ) {
    return kb != null
        && taskId.equals(kb.getQuestionGenTaskId())
        && kb.getQuestionGenStatus() == expectedStatus;
  }

  private boolean isActive(QuestionGenStatus status) {
    return status == QuestionGenStatus.QUEUED || status == QuestionGenStatus.PROCESSING;
  }

  private boolean isStale(LocalDateTime updatedAt, LocalDateTime threshold) {
    return updatedAt == null || updatedAt.isBefore(threshold);
  }

  private String writeConfig(QuestionGenerationConfig config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目生成配置失败", e);
    }
  }

  private QuestionGenerationConfig readConfigOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(value, QuestionGenerationConfig.class);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析题目生成配置失败", e);
    }
  }

  private QuestionGenStatusResponse toResponse(
      KnowledgeBaseEntity kb,
      QuestionGenerationConfig config
  ) {
    return new QuestionGenStatusResponse(
        kb.getId(),
        kb.getQuestionGenStatus() == null ? QuestionGenStatus.NONE : kb.getQuestionGenStatus(),
        kb.getQuestionGenTaskId(),
        config,
        kb.getQuestionGenSavedCount() == null ? 0 : kb.getQuestionGenSavedCount(),
        kb.getQuestionGenSkippedCount() == null ? 0 : kb.getQuestionGenSkippedCount(),
        kb.getQuestionGenMessage(),
        kb.getQuestionGenError(),
        kb.getQuestionGenUpdatedAt()
    );
  }
}
