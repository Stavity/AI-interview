package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MultiRoundQuestionRepository extends JpaRepository<MultiRoundQuestionEntity, Long> { List<MultiRoundQuestionEntity> findByRoundIdOrderBySequenceNoAsc(Long roundId); }
