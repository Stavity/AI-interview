package interview.guide.modules.multiround.repository;
import interview.guide.modules.multiround.model.MultiRoundIdempotencyEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MultiRoundIdempotencyRepository extends JpaRepository<MultiRoundIdempotencyEntity, Long> { Optional<MultiRoundIdempotencyEntity> findBySessionIdAndOperationTypeAndIdempotencyKey(String sessionId, String operationType, String idempotencyKey); }
