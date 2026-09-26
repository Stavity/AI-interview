package interview.guide.modules.questiontag.repository;

import interview.guide.modules.questiontag.model.QuestionTaxonomyVersionEntity;
import interview.guide.modules.questiontag.model.QuestionTaxonomyVersionEntity.Status;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionTaxonomyVersionRepository
    extends JpaRepository<QuestionTaxonomyVersionEntity, Integer> {

  Optional<QuestionTaxonomyVersionEntity> findFirstByStatusOrderByVersionDesc(Status status);
}
