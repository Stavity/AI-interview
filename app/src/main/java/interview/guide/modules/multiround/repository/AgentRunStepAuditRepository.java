package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.AgentRunStepAuditEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunStepAuditRepository extends JpaRepository<AgentRunStepAuditEntity, Long> {
  List<AgentRunStepAuditEntity> findByRunIdOrderByStepNoAsc(Long runId);
}
