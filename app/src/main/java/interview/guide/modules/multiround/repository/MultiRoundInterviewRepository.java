package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MultiRoundInterviewRepository extends JpaRepository<MultiRoundInterviewEntity, Long> { Optional<MultiRoundInterviewEntity> findByInterviewSessionId(Long interviewSessionId); }
