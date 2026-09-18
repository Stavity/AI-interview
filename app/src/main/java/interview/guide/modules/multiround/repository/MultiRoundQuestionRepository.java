package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MultiRoundQuestionRepository extends JpaRepository<MultiRoundQuestionEntity, Long> {
  List<MultiRoundQuestionEntity> findByRoundIdOrderBySequenceNoAsc(Long roundId);

  /** 批量取题目，避免 MultiRoundPlanningContextFactory 按轮次逐条查询造成 N+1。 */
  List<MultiRoundQuestionEntity> findByRoundIdInOrderByRoundIdAscSequenceNoAsc(Collection<Long> roundIds);
}
