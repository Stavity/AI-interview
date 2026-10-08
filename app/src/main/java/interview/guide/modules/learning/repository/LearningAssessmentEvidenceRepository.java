package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningAssessmentEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LearningAssessmentEvidenceRepository
    extends JpaRepository<LearningAssessmentEvidenceEntity, Long> {

  List<LearningAssessmentEvidenceEntity> findByTaxonomyVersionOrderByAssessedAtDesc(int taxonomyVersion);

  @Modifying
  @Query(value = """
      INSERT INTO learning_assessment_evidence (
        owner_user_id, source_type, source_session_id, source_answer_id, source_task_id,
        evaluation_version, question_index, taxonomy_version, node_code, tag_role,
        score, verified, assessed_at, evaluation_fingerprint
      ) VALUES (
        :ownerUserId, 'TEXT_INTERVIEW', :sessionId, :answerId, :taskId,
        :evaluationVersion, :questionIndex, :taxonomyVersion, :nodeCode, :tagRole,
        :score, true, :assessedAt, :fingerprint
      ) ON CONFLICT ON CONSTRAINT uk_learning_evidence_idempotency DO NOTHING
      """, nativeQuery = true)
  int insertTextInterviewEvidence(
      @Param("ownerUserId") long ownerUserId,
      @Param("sessionId") String sessionId,
      @Param("answerId") long answerId,
      @Param("taskId") UUID taskId,
      @Param("evaluationVersion") long evaluationVersion,
      @Param("questionIndex") int questionIndex,
      @Param("taxonomyVersion") int taxonomyVersion,
      @Param("nodeCode") String nodeCode,
      @Param("tagRole") String tagRole,
      @Param("score") int score,
      @Param("assessedAt") LocalDateTime assessedAt,
      @Param("fingerprint") String fingerprint
  );
}
