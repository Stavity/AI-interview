package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningPathItemEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningPathItemRepository extends JpaRepository<LearningPathItemEntity, Long> {

  List<LearningPathItemEntity> findByPathIdAndOwnerUserIdOrderBySequenceNoAsc(long pathId, long ownerUserId);

  Optional<LearningPathItemEntity> findByIdAndOwnerUserId(long id, long ownerUserId);
}
