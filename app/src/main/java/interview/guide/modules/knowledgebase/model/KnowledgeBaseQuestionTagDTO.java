package interview.guide.modules.knowledgebase.model;

import java.math.BigDecimal;

public record KnowledgeBaseQuestionTagDTO(
    String code,
    String name,
    String relationType,
    BigDecimal relevanceWeight,
    String taggingSource,
    BigDecimal confidence
) {}
