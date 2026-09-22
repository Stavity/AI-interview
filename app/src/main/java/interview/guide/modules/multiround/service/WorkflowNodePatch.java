package interview.guide.modules.multiround.service;

import java.util.List;

/** RootAgent may tune only these future-node fields; it cannot replace identity or ordering. */
public record WorkflowNodePatch(String nodeKey, int questionLimit, String difficultyBand, List<String> coverage) {}
