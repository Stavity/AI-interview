package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;
import java.util.List;
import interview.guide.modules.questiontag.model.QuestionSourceRef;

public record KnowledgeBaseQuestionDTO(
    Long id,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String skillId,
    String difficulty,
    String type,
    String category,
    String question,
    String topicSummary,
    String referenceAnswer,
    List<String> keyPoints,
    String scoringRubric,
    List<KnowledgeBaseQuestionFollowUpDTO> followUps,
    String sourceContext,
    KnowledgeBaseQuestionStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Integer difficultyLevel,
    String questionType,
    String primaryKnowledgeCode,
    List<String> secondaryKnowledgeCodes,
    List<KnowledgeBaseQuestionTagDTO> knowledgeTags,
    String sourceType,
    QuestionSourceRef sourceRef,
    Integer taxonomyVersion,
    String taggingStatus
) {
}
