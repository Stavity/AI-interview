package interview.guide.modules.questiontag.dto;

import java.util.List;

public record QuestionTagNodeDetailResponse(
    Integer taxonomyVersion,
    QuestionTagNodeResponse node,
    List<QuestionTagNodeResponse> path,
    List<RelationResponse> relations
) {
  public record RelationResponse(String sourceCode, String targetCode, String relationType) {}
}
