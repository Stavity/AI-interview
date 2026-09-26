package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import interview.guide.modules.questiontag.model.QuestionSourceType;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.model.QuestionType;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_base_questions", indexes = {
    @Index(name = "idx_kb_question_kb_status", columnList = "knowledge_base_id,status"),
    @Index(name = "idx_kb_question_skill_difficulty", columnList = "skill_id,difficulty")
})
public class KnowledgeBaseQuestionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "knowledge_base_id", nullable = false)
  private KnowledgeBaseEntity knowledgeBase;

  @Column(name = "knowledge_base_id", insertable = false, updatable = false)
  private Long knowledgeBaseId;

  // 兼容旧面试会话表的 skill_id 非空约束，知识库题目固定写这个默认值
  public static final String DEFAULT_SKILL_ID = "knowledge-base";

  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId = DEFAULT_SKILL_ID;

  @Column(length = 16)
  private String difficulty;

  @Column(length = 64)
  private String type;

  // 面试方向，由模型生成（例如"整洁架构"/"Redis"/"测试策略"），用于筛选和开始面试
  @Column(length = 64)
  private String category;

  @Column(name = "difficulty_level")
  private Integer difficultyLevel;

  @Enumerated(EnumType.STRING)
  @Column(name = "question_type", length = 32)
  private QuestionType questionType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 32)
  private QuestionSourceType sourceType;

  @Column(name = "source_ref", columnDefinition = "TEXT")
  private String sourceRefJson;

  @Column(name = "taxonomy_version")
  private Integer taxonomyVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "tagging_status", nullable = false, length = 24)
  private QuestionTaggingStatus taggingStatus = QuestionTaggingStatus.REVIEW_REQUIRED;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String question;

  @Column(length = 300)
  private String topicSummary;

  @Column(columnDefinition = "TEXT")
  private String referenceAnswer;

  @Column(columnDefinition = "TEXT")
  private String keyPointsJson;

  @Column(columnDefinition = "TEXT")
  private String scoringRubric;

  @Column(columnDefinition = "TEXT")
  private String followUpsJson;

  @Column(columnDefinition = "TEXT")
  private String sourceContext;

  @Column(length = 64)
  private String kbContentHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private KnowledgeBaseQuestionStatus status = KnowledgeBaseQuestionStatus.DRAFT;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public KnowledgeBaseEntity getKnowledgeBase() {
    return knowledgeBase;
  }

  public void setKnowledgeBase(KnowledgeBaseEntity knowledgeBase) {
    this.knowledgeBase = knowledgeBase;
  }

  public Long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public String getSkillId() {
    return skillId;
  }

  public void setSkillId(String skillId) {
    this.skillId = skillId;
  }

  public String getDifficulty() {
    return difficulty;
  }

  public void setDifficulty(String difficulty) {
    this.difficulty = difficulty;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public Integer getDifficultyLevel() {
    return difficultyLevel;
  }

  public void setDifficultyLevel(Integer difficultyLevel) {
    this.difficultyLevel = difficultyLevel;
  }

  public QuestionType getQuestionType() {
    return questionType;
  }

  public void setQuestionType(QuestionType questionType) {
    this.questionType = questionType;
  }

  public QuestionSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(QuestionSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceRefJson() {
    return sourceRefJson;
  }

  public void setSourceRefJson(String sourceRefJson) {
    this.sourceRefJson = sourceRefJson;
  }

  public Integer getTaxonomyVersion() {
    return taxonomyVersion;
  }

  public void setTaxonomyVersion(Integer taxonomyVersion) {
    this.taxonomyVersion = taxonomyVersion;
  }

  public QuestionTaggingStatus getTaggingStatus() {
    return taggingStatus;
  }

  public void setTaggingStatus(QuestionTaggingStatus taggingStatus) {
    this.taggingStatus = taggingStatus;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public String getTopicSummary() {
    return topicSummary;
  }

  public void setTopicSummary(String topicSummary) {
    this.topicSummary = topicSummary;
  }

  public String getReferenceAnswer() {
    return referenceAnswer;
  }

  public void setReferenceAnswer(String referenceAnswer) {
    this.referenceAnswer = referenceAnswer;
  }

  public String getKeyPointsJson() {
    return keyPointsJson;
  }

  public void setKeyPointsJson(String keyPointsJson) {
    this.keyPointsJson = keyPointsJson;
  }

  public String getScoringRubric() {
    return scoringRubric;
  }

  public void setScoringRubric(String scoringRubric) {
    this.scoringRubric = scoringRubric;
  }

  public String getFollowUpsJson() {
    return followUpsJson;
  }

  public void setFollowUpsJson(String followUpsJson) {
    this.followUpsJson = followUpsJson;
  }

  public String getSourceContext() {
    return sourceContext;
  }

  public void setSourceContext(String sourceContext) {
    this.sourceContext = sourceContext;
  }

  public String getKbContentHash() {
    return kbContentHash;
  }

  public void setKbContentHash(String kbContentHash) {
    this.kbContentHash = kbContentHash;
  }

  public KnowledgeBaseQuestionStatus getStatus() {
    return status;
  }

  public void setStatus(KnowledgeBaseQuestionStatus status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
