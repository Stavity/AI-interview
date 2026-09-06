package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.MultiRoundWorkflowDecisionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MultiRoundWorkflowDecisionRepository extends JpaRepository<MultiRoundWorkflowDecisionEntity, Long> {
  Optional<MultiRoundWorkflowDecisionEntity> findByWorkflowIdAndCheckpointKeyAndDecisionTypeAndStatus(
      Long workflowId, String checkpointKey, String decisionType, MultiRoundWorkflowDecisionEntity.Status status);
}
