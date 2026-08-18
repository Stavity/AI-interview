package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.AgentRunAuditEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentRunAuditRepository extends JpaRepository<AgentRunAuditEntity, Long> {
  List<AgentRunAuditEntity> findByInterviewIdOrderByStartedAtDesc(Long interviewId);
}
