package interview.guide.modules.questiontag.repository;

import interview.guide.modules.questiontag.model.QuestionKnowledgeTagEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeTagEntity.Id;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionKnowledgeTagRepository extends JpaRepository<QuestionKnowledgeTagEntity, Id> {

  List<QuestionKnowledgeTagEntity> findByIdQuestionIdOrderByRelationTypeAscIdNodeCodeAsc(Long questionId);

  List<QuestionKnowledgeTagEntity> findByIdQuestionIdIn(Collection<Long> questionIds);

  void deleteByIdQuestionId(Long questionId);
}
