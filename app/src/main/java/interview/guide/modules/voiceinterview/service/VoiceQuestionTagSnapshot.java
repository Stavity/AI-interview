package interview.guide.modules.voiceinterview.service;

import java.util.List;

/** Immutable knowledge-tag metadata captured when a voice question is generated. */
public record VoiceQuestionTagSnapshot(
    int difficultyLevel,
    String questionType,
    String primaryKnowledgeCode,
    List<String> secondaryKnowledgeCodes,
    int taxonomyVersion,
    String taggingStatus
) {
  public VoiceQuestionTagSnapshot {
    secondaryKnowledgeCodes = secondaryKnowledgeCodes == null
        ? List.of() : List.copyOf(secondaryKnowledgeCodes);
  }
}
