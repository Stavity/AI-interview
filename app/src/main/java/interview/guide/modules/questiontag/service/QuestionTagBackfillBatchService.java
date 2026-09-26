package interview.guide.modules.questiontag.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.questiontag.model.QuestionKnowledgeTagEntity;
import interview.guide.modules.questiontag.model.QuestionTagRelationType;
import interview.guide.modules.questiontag.model.QuestionTaggingSource;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeTagRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionTagBackfillBatchService {

  private static final TypeReference<List<InterviewQuestionDTO>> QUESTIONS_TYPE = new TypeReference<>() {};
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final QuestionKnowledgeTagRepository questionTagRepository;
  private final InterviewSessionRepository sessionRepository;
  private final QuestionTagBackfillMatcher matcher;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public BatchResult backfillKnowledgeBaseBatch(
      int taxonomyVersion, Long afterId, int batchSize, boolean dryRun
  ) {
    Pageable pageable = PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "id"));
    List<KnowledgeBaseQuestionEntity> questions = questionRepository
        .findByTaggingStatusAndIdGreaterThanOrderByIdAsc(
            QuestionTaggingStatus.LEGACY_UNTAGGED, afterId, pageable);
    int tagged = 0;
    int review = 0;
    int unmatched = 0;
    int skipped = 0;
    Long cursor = afterId;

    for (KnowledgeBaseQuestionEntity question : questions) {
      cursor = question.getId();
      List<QuestionKnowledgeTagEntity> existing = questionTagRepository
          .findByIdQuestionIdOrderByRelationTypeAscIdNodeCodeAsc(question.getId());
      if (!existing.isEmpty()) {
        questionRepository.updateBackfillMetadata(question.getId(), existing.get(0).getTaxonomyVersion(),
            QuestionTaggingStatus.REVIEW_REQUIRED, QuestionTaggingStatus.LEGACY_UNTAGGED);
        skipped++;
        continue;
      }
      QuestionTagBackfillMatcher.MatchResult match = matcher.match(taxonomyVersion,
          question.getSkillId(), question.getType(), question.getCategory(),
          question.getTopicSummary(), question.getQuestion());
      QuestionTaggingStatus status = match.verified()
          ? QuestionTaggingStatus.VERIFIED : QuestionTaggingStatus.REVIEW_REQUIRED;
      if (!match.matched()) {
        unmatched++;
        review++;
      } else if (match.verified()) {
        tagged++;
      } else {
        review++;
      }
      if (dryRun) {
        continue;
      }

      int updated = questionRepository.updateBackfillMetadata(question.getId(), taxonomyVersion,
          status, QuestionTaggingStatus.LEGACY_UNTAGGED);
      if (updated != 1) {
        skipped++;
        continue;
      }
      if (match.matched()) {
        questionTagRepository.save(QuestionKnowledgeTagEntity.create(
            question.getId(), taxonomyVersion, match.primaryCode(), QuestionTagRelationType.PRIMARY,
            QuestionTaggingSource.BACKFILLED, BigDecimal.ONE, match.confidence()));
      }
    }
    return new BatchResult(questions.size(), tagged, review, unmatched, skipped, 0, cursor);
  }

  @Transactional(rollbackFor = Exception.class)
  public BatchResult backfillInterviewSessionBatch(
      int taxonomyVersion, Long afterId, int batchSize, boolean dryRun
  ) {
    Pageable pageable = PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "id"));
    List<InterviewSessionEntity> sessions = sessionRepository.findBackfillCandidates(
        afterId, pageable);
    int tagged = 0;
    int review = 0;
    int unmatched = 0;
    int skipped = 0;
    int parseErrors = 0;
    Long cursor = afterId;

    for (InterviewSessionEntity session : sessions) {
      cursor = session.getId();
      String originalJson = session.getQuestionsJson();
      List<InterviewQuestionDTO> questions;
      try {
        questions = objectMapper.readValue(originalJson, QUESTIONS_TYPE);
      } catch (Exception e) {
        parseErrors++;
        log.warn("历史面试问题 JSON 无法解析，跳过标签回填: sessionId={}", session.getSessionId());
        continue;
      }
      if (questions == null || questions.isEmpty()) {
        skipped++;
        continue;
      }

      List<InterviewQuestionDTO> updatedQuestions = new ArrayList<>(questions.size());
      boolean changed = false;
      for (InterviewQuestionDTO question : questions) {
        if (question == null) {
          updatedQuestions.add(null);
          continue;
        }
        if (question.taggingSource() != null) {
          updatedQuestions.add(question);
          continue;
        }
        if (question.primaryKnowledgeCode() != null && !question.primaryKnowledgeCode().isBlank()) {
          updatedQuestions.add(question.withTaggingProvenance(
              QuestionTaggingSource.LEGACY.name(), question.tagConfidence()));
          changed = true;
          skipped++;
          continue;
        }

        QuestionTagBackfillMatcher.MatchResult match = matcher.match(taxonomyVersion,
            session.getSkillId(), question.type(), question.category(), question.topicSummary(),
            question.question());
        QuestionTaggingStatus status = match.verified()
            ? QuestionTaggingStatus.VERIFIED : QuestionTaggingStatus.REVIEW_REQUIRED;
        if (!match.matched()) {
          unmatched++;
          review++;
        } else if (match.verified()) {
          tagged++;
        } else {
          review++;
        }
        updatedQuestions.add(question.withBackfillTagMetadata(
            match.primaryCode(), List.of(), taxonomyVersion, status.name(),
            QuestionTaggingSource.BACKFILLED.name(), match.confidence()));
        changed = true;
      }
      if (!changed) {
        continue;
      }
      if (dryRun) {
        continue;
      }
      try {
        String updatedJson = objectMapper.writeValueAsString(updatedQuestions);
        if (sessionRepository.updateQuestionsJsonIfUnchanged(
            session.getId(), originalJson, updatedJson) != 1) {
          skipped++;
        }
      } catch (JacksonException e) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化历史面试标签快照失败", e);
      }
    }
    return new BatchResult(sessions.size(), tagged, review, unmatched, skipped, parseErrors, cursor);
  }

  public record BatchResult(
      int scanned,
      int autoTagged,
      int reviewRequired,
      int unmatched,
      int skipped,
      int parseErrors,
      Long cursor
  ) {}
}
