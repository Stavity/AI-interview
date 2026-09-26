package interview.guide.modules.questiontag.repository;

import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeAliasEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionKnowledgeNodeAliasRepository
    extends JpaRepository<QuestionKnowledgeNodeAliasEntity, Long> {

  List<QuestionKnowledgeNodeAliasEntity> findByTaxonomyVersion(Integer taxonomyVersion);
}
