package interview.guide.modules.questiontag.repository;

import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionKnowledgeNodeRepository extends JpaRepository<QuestionKnowledgeNodeEntity, Long> {

  List<QuestionKnowledgeNodeEntity> findByTaxonomyVersionOrderBySortOrderAscCodeAsc(Integer taxonomyVersion);

  List<QuestionKnowledgeNodeEntity> findByTaxonomyVersionAndStatusOrderBySortOrderAscCodeAsc(
      Integer taxonomyVersion, Status status);

  List<QuestionKnowledgeNodeEntity> findByTaxonomyVersionAndParentCodeAndStatusOrderBySortOrderAscCodeAsc(
      Integer taxonomyVersion, String parentCode, Status status);

  Optional<QuestionKnowledgeNodeEntity> findByTaxonomyVersionAndCode(Integer taxonomyVersion, String code);
}
