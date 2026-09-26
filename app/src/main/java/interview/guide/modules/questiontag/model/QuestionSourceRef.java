package interview.guide.modules.questiontag.model;

import java.util.List;

public record QuestionSourceRef(
    Long knowledgeBaseId,
    String documentHash,
    List<String> chunkIds,
    String sourceLabel,
    Generator generator
) {
  public QuestionSourceRef(Long knowledgeBaseId, String documentHash, List<String> chunkIds,
                           String sourceLabel) {
    this(knowledgeBaseId, documentHash, chunkIds, sourceLabel, null);
  }

  public QuestionSourceRef {
    chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
  }

  public record Generator(String providerId, String model, String promptVersion) {}
}
