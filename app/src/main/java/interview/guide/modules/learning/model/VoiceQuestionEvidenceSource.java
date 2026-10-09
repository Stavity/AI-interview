package interview.guide.modules.learning.model;

import java.util.List;

/** Frozen voice-question identity and tags carried by the asynchronous evaluation snapshot. */
public record VoiceQuestionEvidenceSource(
    Long sourceMessageId,
    int questionIndex,
    Integer taxonomyVersion,
    String primaryKnowledgeCode,
    List<String> secondaryKnowledgeCodes,
    String taggingStatus
) {
  public VoiceQuestionEvidenceSource {
    secondaryKnowledgeCodes = secondaryKnowledgeCodes == null
        ? List.of() : List.copyOf(secondaryKnowledgeCodes);
  }
}
