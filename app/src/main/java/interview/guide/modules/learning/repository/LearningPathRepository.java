package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningPathEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningPathRepository extends JpaRepository<LearningPathEntity, Long> {

  Optional<LearningPathEntity> findFirstByOwnerUserIdAndStatusOrderByIdDesc(
      long ownerUserId, LearningPathEntity.Status status
  );
}
