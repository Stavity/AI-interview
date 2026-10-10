package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningPathItemResourceEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningPathItemResourceRepository
    extends JpaRepository<LearningPathItemResourceEntity, Long> {

  List<LearningPathItemResourceEntity> findByPathItemIdInAndOwnerUserIdOrderByMatchScoreDescIdAsc(
      Collection<Long> pathItemIds, long ownerUserId);
}
