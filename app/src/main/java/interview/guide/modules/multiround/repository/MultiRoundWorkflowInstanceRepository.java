package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MultiRoundWorkflowInstanceRepository
    extends JpaRepository<MultiRoundWorkflowInstanceEntity, Long> {
  Optional<MultiRoundWorkflowInstanceEntity> findByInterviewId(Long interviewId);
  List<MultiRoundWorkflowInstanceEntity> findByStatusAndLeaseUntilBefore(
      MultiRoundWorkflowInstanceEntity.Status status, LocalDateTime before);
}
