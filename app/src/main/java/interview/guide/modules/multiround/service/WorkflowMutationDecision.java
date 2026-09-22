package interview.guide.modules.multiround.service;

import java.util.List;

/** Checkpoint proposal for existing future nodes. */
public record WorkflowMutationDecision(long expectedRevision, String rationale, List<WorkflowNodePatch> patches) {}
