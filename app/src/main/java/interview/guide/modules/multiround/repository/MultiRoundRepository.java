package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MultiRoundRepository extends JpaRepository<MultiRoundEntity, Long> {
  List<MultiRoundEntity> findByInterviewIdOrderByRoundNoAsc(Long interviewId);

  /**
   * 悲观写锁加载轮次，用于 commitStart 在 LLM 窗口之后重新校验前置条件，
   * 防止并发请求在 LLM 执行期间改变轮次状态。
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from MultiRoundEntity r where r.id = :id")
  Optional<MultiRoundEntity> findByIdForUpdate(@Param("id") Long id);
}
