package interview.guide.infrastructure.async;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface AsyncTaskRepository extends JpaRepository<AsyncTaskEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AsyncTaskEntity t where t.id = :id")
    Optional<AsyncTaskEntity> lock(@Param("id") UUID id);
    Optional<AsyncTaskEntity> findByActiveKey(String activeKey);
    @Query(value = """
        select * from async_task where
        (state = 'RUNNING' and lease_until < CURRENT_TIMESTAMP)
        or (state in ('QUEUED', 'RETRY_WAIT') and next_run_at < CURRENT_TIMESTAMP
            and updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes')
        order by updated_at limit 50 for update skip locked
        """, nativeQuery = true)
    List<AsyncTaskEntity> lockRecoverable();
}
