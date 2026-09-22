package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MultiRoundWorkflowInstanceRepository
    extends JpaRepository<MultiRoundWorkflowInstanceEntity, Long> {
  Optional<MultiRoundWorkflowInstanceEntity> findByInterviewId(Long interviewId);
}
