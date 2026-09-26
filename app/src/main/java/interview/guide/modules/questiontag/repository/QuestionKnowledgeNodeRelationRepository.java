package interview.guide.modules.questiontag.repository;

import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeRelationEntity.Id;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionKnowledgeNodeRelationRepository extends JpaRepository<QuestionKnowledgeNodeRelationEntity, Id> {

  List<QuestionKnowledgeNodeRelationEntity> findByIdTaxonomyVersion(Integer taxonomyVersion);
}
