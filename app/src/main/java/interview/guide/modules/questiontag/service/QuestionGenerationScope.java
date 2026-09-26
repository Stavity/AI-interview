package interview.guide.modules.questiontag.service;

import interview.guide.modules.questiontag.model.QuestionSourceType;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;

/** Immutable security boundary captured for one question-generation run. */
public record QuestionGenerationScope(
    String skillId,
    QuestionSourceType sourceType,
    int taxonomyVersion,
    Set<String> allowedRootCodes,
    int maxToolCalls
) {
  public QuestionGenerationScope {
    if (skillId == null || skillId.isBlank()) {
      throw new IllegalArgumentException("skillId must not be blank");
    }
    if (sourceType == null) {
      throw new IllegalArgumentException("sourceType must not be null");
    }
    allowedRootCodes = allowedRootCodes == null
        ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(allowedRootCodes));
    if (allowedRootCodes.isEmpty()) {
      throw new IllegalArgumentException("allowedRootCodes must not be empty");
    }
    if (taxonomyVersion < 1 || maxToolCalls < 1) {
      throw new IllegalArgumentException("taxonomyVersion and maxToolCalls must be positive");
    }
  }
}
