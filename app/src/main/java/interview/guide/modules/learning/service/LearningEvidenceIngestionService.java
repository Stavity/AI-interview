package interview.guide.modules.learning.service;

import interview.guide.common.tenant.TenantContext;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.learning.repository.LearningAssessmentEvidenceRepository;
import interview.guide.modules.learning.model.VoiceQuestionEvidenceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LearningEvidenceIngestionService {
  private final LearningAssessmentEvidenceRepository repository;
  private final LearningKnowledgeProgressService progressService;
  private final PersonalizedLearningPathService personalizedLearningPathService;

  public int persistTextInterviewEvidence(
      String sessionId,
      List<InterviewQuestionDTO> questions,
      List<InterviewAnswerEntity> answers,
      InterviewReportDTO report,
      UUID taskId,
      long evaluationVersion
  ) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    Map<Integer, InterviewQuestionDTO> questionByIndex = questions.stream()
        .collect(Collectors.toMap(InterviewQuestionDTO::questionIndex, Function.identity(), (a, b) -> a));
    Map<Integer, InterviewAnswerEntity> answerByIndex = answers.stream()
        .collect(Collectors.toMap(InterviewAnswerEntity::getQuestionIndex, Function.identity(), (a, b) -> a));
    LocalDateTime assessedAt = LocalDateTime.now();
    int inserted = 0;

    for (InterviewReportDTO.QuestionEvaluation evaluation : report.questionDetails()) {
      InterviewQuestionDTO question = questionByIndex.get(evaluation.questionIndex());
      InterviewAnswerEntity answer = answerByIndex.get(evaluation.questionIndex());
      if (!isEligible(question, answer, evaluation)) {
        continue;
      }

      List<TaggedNode> nodes = taggedNodes(question);
      for (TaggedNode node : nodes) {
        String fingerprint = fingerprint(sessionId, answer.getId(), evaluation, question.taxonomyVersion(),
            node.code(), node.role());
        inserted += repository.insertTextInterviewEvidence(
            ownerUserId, sessionId, answer.getId(), taskId, evaluationVersion,
            evaluation.questionIndex(), question.taxonomyVersion(), node.code(), node.role(),
            evaluation.score(), assessedAt, fingerprint);
      }
    }
    if (inserted > 0) {
      questions.stream()
          .map(InterviewQuestionDTO::taxonomyVersion)
          .filter(java.util.Objects::nonNull)
          .distinct()
          .forEach(version -> {
            progressService.rebuild(ownerUserId, version);
            personalizedLearningPathService.refreshAfterKnowledgeProgressChanged(ownerUserId, version);
          });
    }
    return inserted;
  }

  public int persistVoiceInterviewEvidence(
      String sessionId,
      List<VoiceQuestionEvidenceSource> sources,
      EvaluationReport report,
      UUID taskId,
      long evaluationVersion
  ) {
    long ownerUserId = TenantContext.requireCurrentTenantId();
    Map<Integer, VoiceQuestionEvidenceSource> sourceByIndex = sources.stream()
        .collect(Collectors.toMap(
            VoiceQuestionEvidenceSource::questionIndex, Function.identity(), (a, b) -> a));
    LocalDateTime assessedAt = LocalDateTime.now();
    int inserted = 0;

    for (EvaluationReport.QuestionEvaluation evaluation : report.questionDetails()) {
      VoiceQuestionEvidenceSource source = sourceByIndex.get(evaluation.questionIndex());
      if (!isEligible(source, evaluation)) {
        continue;
      }
      for (TaggedNode node : taggedNodes(
          source.primaryKnowledgeCode(), source.secondaryKnowledgeCodes())) {
        String fingerprint = fingerprint(
            sessionId, source.sourceMessageId(), evaluation.questionIndex(), evaluation.score(),
            evaluation.feedback(), source.taxonomyVersion(), node.code(), node.role());
        inserted += repository.insertVoiceInterviewEvidence(
            ownerUserId, sessionId, source.sourceMessageId(), taskId, evaluationVersion,
            evaluation.questionIndex(), source.taxonomyVersion(), node.code(), node.role(),
            evaluation.score(), assessedAt, fingerprint);
      }
    }
    if (inserted > 0) {
      sources.stream()
          .filter(source -> source.taxonomyVersion() != null)
          .mapToInt(VoiceQuestionEvidenceSource::taxonomyVersion)
          .distinct()
          .forEach(version -> {
            progressService.rebuild(ownerUserId, version);
            personalizedLearningPathService.refreshAfterKnowledgeProgressChanged(ownerUserId, version);
          });
    }
    return inserted;
  }

  private static boolean isEligible(
      InterviewQuestionDTO question,
      InterviewAnswerEntity answer,
      InterviewReportDTO.QuestionEvaluation evaluation
  ) {
    return question != null
        && answer != null
        && answer.getId() != null
        && "VERIFIED".equals(question.taggingStatus())
        && question.taxonomyVersion() != null
        && question.taxonomyVersion() > 0
        && question.primaryKnowledgeCode() != null
        && !question.primaryKnowledgeCode().isBlank()
        && evaluation.score() >= 0
        && evaluation.score() <= 100;
  }

  private static List<TaggedNode> taggedNodes(InterviewQuestionDTO question) {
    return taggedNodes(question.primaryKnowledgeCode(), question.secondaryKnowledgeCodes());
  }

  private static List<TaggedNode> taggedNodes(String primaryCode, List<String> secondaryCodes) {
    List<TaggedNode> result = new ArrayList<>();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    String primary = primaryCode.trim();
    seen.add(primary);
    result.add(new TaggedNode(primary, "PRIMARY"));
    for (String secondary : secondaryCodes) {
      if (secondary != null && !secondary.isBlank() && seen.add(secondary.trim())) {
        result.add(new TaggedNode(secondary.trim(), "SECONDARY"));
      }
    }
    return result;
  }

  private static boolean isEligible(
      VoiceQuestionEvidenceSource source,
      EvaluationReport.QuestionEvaluation evaluation
  ) {
    return source != null
        && source.sourceMessageId() != null
        && source.sourceMessageId() > 0
        && "VERIFIED".equals(source.taggingStatus())
        && source.taxonomyVersion() != null
        && source.taxonomyVersion() > 0
        && source.primaryKnowledgeCode() != null
        && !source.primaryKnowledgeCode().isBlank()
        && evaluation.score() >= 0
        && evaluation.score() <= 100;
  }

  private static String fingerprint(
      String sessionId,
      long answerId,
      InterviewReportDTO.QuestionEvaluation evaluation,
      int taxonomyVersion,
      String nodeCode,
      String role
  ) {
    return fingerprint(sessionId, answerId, evaluation.questionIndex(), evaluation.score(),
        evaluation.feedback(), taxonomyVersion, nodeCode, role);
  }

  private static String fingerprint(
      String sessionId,
      long sourceRecordId,
      int questionIndex,
      int score,
      String feedback,
      int taxonomyVersion,
      String nodeCode,
      String role
  ) {
    String value = String.join("\u001f",
        sessionId,
        Long.toString(sourceRecordId),
        Integer.toString(questionIndex),
        Integer.toString(score),
        feedback == null ? "" : feedback,
        Integer.toString(taxonomyVersion),
        nodeCode,
        role);
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private record TaggedNode(String code, String role) {}
}
