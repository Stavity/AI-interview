package interview.guide.modules.multiround.service;

import java.util.List;

/** Model-facing specification. It has no persistence identity and is validated before materialization. */
public record WorkflowNodeDefinition(
    String nodeKey, String nodeType, int questionLimit, String difficultyBand, List<String> coverage) {
}
