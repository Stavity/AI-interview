package interview.guide.modules.questiontag.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class QuestionTagCatalogDefinition {
  private Integer version;
  private List<NodeDefinition> nodes = new ArrayList<>();
  private List<RelationDefinition> relations = new ArrayList<>();

  @Data
  @NoArgsConstructor
  public static class NodeDefinition {
    private String code;
    private String name;
    private String nodeKind;
    private String description;
    private Boolean assignable;
    private List<String> aliases = new ArrayList<>();
    private List<NodeDefinition> children = new ArrayList<>();
  }

  @Data
  @NoArgsConstructor
  public static class RelationDefinition {
    private String sourceCode;
    private String targetCode;
    private String relationType;
  }
}
