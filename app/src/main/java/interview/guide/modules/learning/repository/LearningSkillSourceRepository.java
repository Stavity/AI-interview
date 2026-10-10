package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearningSkillSourceRepository extends JpaRepository<LearningSkillSourceEntity, Long> {

  Optional<LearningSkillSourceEntity> findBySkillIdAndCategoryKey(String skillId, String categoryKey);

  List<LearningSkillSourceEntity> findByStatusOrderByIdAsc(LearningSkillSourceEntity.Status status);
}
