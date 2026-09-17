package interview.guide.infrastructure.async;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface TaskOutboxRepository extends JpaRepository<TaskOutboxEntity, UUID> {
    @Query(value = """
        select * from task_outbox where available_at <= CURRENT_TIMESTAMP
        and (state = 'PENDING' or (state = 'SENDING' and lease_until < CURRENT_TIMESTAMP))
        order by available_at limit 10 for update skip locked
        """, nativeQuery = true)
    List<TaskOutboxEntity> lockReady();
    boolean existsByTaskIdAndTopicAndStateNot(UUID taskId, String topic, String state);
    @Modifying @Query("""
        update TaskOutboxEntity o set o.state = 'SENT', o.sentAt = :now,
        o.leaseOwner = null, o.leaseUntil = null where o.id = :id and o.leaseOwner = :owner
        """)
    int markSent(@Param("id") UUID id, @Param("owner") UUID owner, @Param("now") Instant now);
    @Modifying @Query("""
        update TaskOutboxEntity o set o.state = 'PENDING', o.availableAt = :next,
        o.leaseOwner = null, o.leaseUntil = null where o.id = :id and o.leaseOwner = :owner
        """)
    int release(@Param("id") UUID id, @Param("owner") UUID owner, @Param("next") Instant next);
}
