package interview.guide.modules.questiontag.model;

import java.util.List;

public record QuestionSourceRef(
    Long knowledgeBaseId,
    String documentHash,
    List<String> chunkIds,
    String sourceLabel
) {
  public QuestionSourceRef {
    chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
  }
}
