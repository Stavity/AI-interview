package interview.guide.modules.questiontag.dto;

import java.util.List;

public record QuestionTagSearchResponse(
    Integer taxonomyVersion,
    String query,
    List<SearchMatch> results
) {
  public record SearchMatch(QuestionTagNodeResponse node, String matchedBy) {}
}
