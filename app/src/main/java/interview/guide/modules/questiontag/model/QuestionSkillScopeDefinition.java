package interview.guide.modules.questiontag.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class QuestionSkillScopeDefinition {
  private Integer version;
  private List<ScopeDefinition> scopes = new ArrayList<>();

  @Data
  @NoArgsConstructor
  public static class ScopeDefinition {
    private String skillId;
    private List<String> roots = new ArrayList<>();
  }
}
