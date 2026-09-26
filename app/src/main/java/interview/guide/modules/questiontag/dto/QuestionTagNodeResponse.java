package interview.guide.modules.questiontag.dto;

import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import java.util.List;

public record QuestionTagNodeResponse(
    String code,
    String name,
    String parentCode,
    String nodeKind,
    String description,
    boolean assignable,
    List<String> aliases
) {
  public static QuestionTagNodeResponse from(
      QuestionKnowledgeNodeEntity node, List<String> aliases
  ) {
    return new QuestionTagNodeResponse(
        node.getCode(), node.getName(), node.getParentCode(), node.getNodeKind(),
        node.getDescription(), node.isAssignable(), aliases
    );
  }
}
