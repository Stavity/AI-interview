package interview.guide.modules.multiround.service;

import java.util.List;

/** RootAgent output for an initial workflow. It is a proposal, never an executable instruction. */
public record InitialWorkflowDecision(String rationale, List<WorkflowNodeDefinition> nodes) {
}
