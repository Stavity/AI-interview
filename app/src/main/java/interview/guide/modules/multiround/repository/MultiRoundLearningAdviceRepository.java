package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundLearningAdviceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MultiRoundLearningAdviceRepository extends JpaRepository<MultiRoundLearningAdviceEntity, Long> {
  Optional<MultiRoundLearningAdviceEntity> findByInterviewId(Long interviewId);
}
