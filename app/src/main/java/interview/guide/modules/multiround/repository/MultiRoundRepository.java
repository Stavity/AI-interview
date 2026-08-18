package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MultiRoundRepository extends JpaRepository<MultiRoundEntity, Long> { List<MultiRoundEntity> findByInterviewIdOrderByRoundNoAsc(Long interviewId); }
