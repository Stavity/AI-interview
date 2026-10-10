package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningKnowledgeProgressEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningKnowledgeProgressRepository
    extends JpaRepository<LearningKnowledgeProgressEntity, Long> {

  List<LearningKnowledgeProgressEntity>
      findByOwnerUserIdAndTaxonomyVersionOrderByNodeCodeAsc(long ownerUserId, int taxonomyVersion);

  long deleteByOwnerUserIdAndTaxonomyVersion(long ownerUserId, int taxonomyVersion);
}
