package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningPlanEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningPlanRepository extends JpaRepository<LearningPlanEntity, Long> {
  List<LearningPlanEntity> findAllByOrderByUpdatedAtDesc();
}
