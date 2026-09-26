package interview.guide.modules.questiontag.repository;

import interview.guide.modules.questiontag.model.QuestionSkillKnowledgeScopeEntity;
import interview.guide.modules.questiontag.model.QuestionSkillKnowledgeScopeEntity.Id;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionSkillKnowledgeScopeRepository
    extends JpaRepository<QuestionSkillKnowledgeScopeEntity, Id> {

  List<QuestionSkillKnowledgeScopeEntity> findByIdTaxonomyVersionAndIdSkillId(
      Integer taxonomyVersion, String skillId);
}
