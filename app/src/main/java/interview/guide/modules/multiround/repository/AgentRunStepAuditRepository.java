package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.AgentRunStepAuditEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunStepAuditRepository extends JpaRepository<AgentRunStepAuditEntity, Long> {
  List<AgentRunStepAuditEntity> findByRunIdOrderByStepNoAsc(Long runId);

  /** 批量取步骤，避免 AgentRunTraceService 按 run 逐条查询造成 N+1。 */
  List<AgentRunStepAuditEntity> findByRunIdInOrderByStepNoAsc(Collection<Long> runIds);
}
