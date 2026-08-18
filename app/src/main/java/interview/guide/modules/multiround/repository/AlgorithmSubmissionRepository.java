package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.AlgorithmSubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface AlgorithmSubmissionRepository extends JpaRepository<AlgorithmSubmissionEntity, Long> {
  Optional<AlgorithmSubmissionEntity> findByQuestionId(Long questionId);
  @Query("select submission from AlgorithmSubmissionEntity submission join fetch submission.question question join fetch question.round round join fetch round.interview interview where submission.id = :id")
  Optional<AlgorithmSubmissionEntity> findForReviewById(@Param("id") Long id);
}
