package interview.guide.modules.learning.service;

import interview.guide.common.tenant.TenantContext;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.learning.repository.LearningAssessmentEvidenceRepository;
import interview.guide.modules.learning.model.VoiceQuestionEvidenceSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningEvidenceIngestionServiceTest {
  @Mock private LearningAssessmentEvidenceRepository repository;
  @Mock private LearningKnowledgeProgressService progressService;

  @Test
  void persistsVerifiedPrimaryAndSecondaryTagsWithTrustedOwner() {
    var service = new LearningEvidenceIngestionService(repository, progressService);
    var answer = answer(101L, 0);
    var report = report(0, 76, "solid");
    UUID taskId = UUID.randomUUID();
    when(repository.insertTextInterviewEvidence(
        anyLong(), anyString(), anyLong(), any(), anyLong(), anyInt(), anyInt(),
        anyString(), anyString(), anyInt(), any(LocalDateTime.class), anyString()))
        .thenReturn(1);

    int inserted = TenantContext.callAs(7L, () -> service.persistTextInterviewEvidence(
        "session-1", List.of(question("VERIFIED")), List.of(answer), report, taskId, 3L));

    assertThat(inserted).isEqualTo(2);
    verify(repository).insertTextInterviewEvidence(
        eq(7L), eq("session-1"), eq(101L), eq(taskId), eq(3L), eq(0), eq(11),
        eq("ROOT.JAVA.COLLECTION"), eq("PRIMARY"), eq(76), any(LocalDateTime.class), anyString());
    verify(repository).insertTextInterviewEvidence(
        eq(7L), eq("session-1"), eq(101L), eq(taskId), eq(3L), eq(0), eq(11),
        eq("ROOT.JAVA.GENERICS"), eq("SECONDARY"), eq(76), any(LocalDateTime.class), anyString());
    verify(progressService).rebuild(7L, 11);
  }

  @Test
  void skipsUnverifiedTagsEvenWhenEvaluationHasAScore() {
    var service = new LearningEvidenceIngestionService(repository, progressService);

    int inserted = TenantContext.callAs(7L, () -> service.persistTextInterviewEvidence(
        "session-1", List.of(question("REVIEW_REQUIRED")), List.of(answer(101L, 0)),
        report(0, 76, "solid"), UUID.randomUUID(), 1L));

    assertThat(inserted).isZero();
    verify(repository, never()).insertTextInterviewEvidence(
        anyLong(), anyString(), anyLong(), any(), anyLong(), anyInt(), anyInt(),
        anyString(), anyString(), anyInt(), any(LocalDateTime.class), anyString());
    verify(progressService, never()).rebuild(anyLong(), anyInt());
  }

  @Test
  void persistsVoiceEvidenceAgainstFrozenMessageAndTaskVersion() {
    var service = new LearningEvidenceIngestionService(repository, progressService);
    UUID taskId = UUID.randomUUID();
    when(repository.insertVoiceInterviewEvidence(
        anyLong(), anyString(), anyLong(), any(), anyLong(), anyInt(), anyInt(),
        anyString(), anyString(), anyInt(), any(LocalDateTime.class), anyString()))
        .thenReturn(1);
    var source = new VoiceQuestionEvidenceSource(
        501L, 0, 11, "ROOT.JAVA.COLLECTION",
        List.of("ROOT.JAVA.GENERICS", "ROOT.JAVA.GENERICS"), "VERIFIED");

    int inserted = TenantContext.callAs(7L, () -> service.persistVoiceInterviewEvidence(
        "88", List.of(source), voiceReport(0, 82, "clear"), taskId, 5L));

    assertThat(inserted).isEqualTo(2);
    verify(repository).insertVoiceInterviewEvidence(
        eq(7L), eq("88"), eq(501L), eq(taskId), eq(5L), eq(0), eq(11),
        eq("ROOT.JAVA.COLLECTION"), eq("PRIMARY"), eq(82),
        any(LocalDateTime.class), anyString());
    verify(repository).insertVoiceInterviewEvidence(
        eq(7L), eq("88"), eq(501L), eq(taskId), eq(5L), eq(0), eq(11),
        eq("ROOT.JAVA.GENERICS"), eq("SECONDARY"), eq(82),
        any(LocalDateTime.class), anyString());
    verify(progressService).rebuild(7L, 11);
  }

  @Test
  void skipsVoiceEvidenceWithoutVerifiedFrozenTags() {
    var service = new LearningEvidenceIngestionService(repository, progressService);
    var source = new VoiceQuestionEvidenceSource(
        501L, 0, 11, "ROOT.JAVA.COLLECTION", List.of(), "REVIEW_REQUIRED");

    int inserted = TenantContext.callAs(7L, () -> service.persistVoiceInterviewEvidence(
        "88", List.of(source), voiceReport(0, 82, "clear"), UUID.randomUUID(), 1L));

    assertThat(inserted).isZero();
    verify(repository, never()).insertVoiceInterviewEvidence(
        anyLong(), anyString(), anyLong(), any(), anyLong(), anyInt(), anyInt(),
        anyString(), anyString(), anyInt(), any(LocalDateTime.class), anyString());
    verify(progressService, never()).rebuild(anyLong(), anyInt());
  }

  private static InterviewQuestionDTO question(String taggingStatus) {
    return new InterviewQuestionDTO(
        0, "Explain collections", "JAVA", "Java", "collections", "answer", null, null,
        false, null, "reference", List.of("point"), "rubric", null, 3, "CONCEPT",
        "ROOT.JAVA.COLLECTION", List.of("ROOT.JAVA.GENERICS", "ROOT.JAVA.GENERICS"),
        "MOCK_INTERVIEW_GENERATED", null, 11, taggingStatus, "LLM", BigDecimal.ONE);
  }

  private static InterviewAnswerEntity answer(long id, int questionIndex) {
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setId(id);
    answer.setQuestionIndex(questionIndex);
    return answer;
  }

  private static InterviewReportDTO report(int questionIndex, int score, String feedback) {
    return new InterviewReportDTO(
        "session-1", 1, score, List.of(),
        List.of(new InterviewReportDTO.QuestionEvaluation(
            questionIndex, "Explain collections", "Java", "answer", score, feedback)),
        "overall", List.of(), List.of(), List.of());
  }

  private static EvaluationReport voiceReport(int questionIndex, int score, String feedback) {
    return new EvaluationReport(
        "88", 1, score, List.of(),
        List.of(new EvaluationReport.QuestionEvaluation(
            questionIndex, "Explain collections", "Java", "answer", score, feedback)),
        "overall", List.of(), List.of(), List.of());
  }
}
