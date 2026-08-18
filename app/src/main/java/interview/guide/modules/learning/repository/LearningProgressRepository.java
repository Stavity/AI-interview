package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningProgressEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningProgressRepository extends JpaRepository<LearningProgressEntity, Long> {

  Optional<LearningProgressEntity> findBySkillSource(LearningSkillSourceEntity skillSource);
}
