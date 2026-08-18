package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningPlanTaskEntity;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningPlanTaskRepository extends JpaRepository<LearningPlanTaskEntity, Long> {
  List<LearningPlanTaskEntity> findByPlanIdOrderByDueDateAscSortOrderAsc(Long planId);

  List<LearningPlanTaskEntity> findByDueDateBetweenOrderByDueDateAscSortOrderAsc(LocalDate start, LocalDate end);
}
