package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.MultiRoundWorkflowEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MultiRoundWorkflowEventRepository extends JpaRepository<MultiRoundWorkflowEventEntity, Long> {
  List<MultiRoundWorkflowEventEntity> findByWorkflowIdOrderByCreatedAtAsc(Long workflowId);
}
