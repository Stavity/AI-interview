package interview.guide.modules.questiontag.dto;

import java.time.LocalDateTime;

public record QuestionTaxonomyVersionResponse(
    Integer version,
    String status,
    String contentHash,
    LocalDateTime publishedAt
) {}
