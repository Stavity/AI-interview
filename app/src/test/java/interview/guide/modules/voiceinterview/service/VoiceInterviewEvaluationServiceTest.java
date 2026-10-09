package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.learning.model.VoiceQuestionEvidenceSource;
import interview.guide.modules.learning.service.LearningEvidenceIngestionService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试评估服务")
class VoiceInterviewEvaluationServiceTest {

    @Mock
    private UnifiedEvaluationService unifiedEvaluationService;

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private VoiceInterviewEvaluationRepository evaluationRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private InterviewSkillService skillService;

    @Mock
    private LearningEvidenceIngestionService learningEvidenceIngestionService;

    @InjectMocks
    private VoiceInterviewEvaluationService evaluationService;

    @Test
    @DisplayName("没有有效对话时应显示暂无评分而不是零分")
    void shouldNotAssignZeroScoreWhenNoConversationExists() {
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(1L)
            .roleType("Java 后端开发")
            .startTime(LocalDateTime.now())
            .build();
        when(evaluationRepository.findBySessionId(1L)).thenReturn(Optional.empty());

        evaluationService.saveEmptyEvaluationTransactional(1L, session);

        ArgumentCaptor<VoiceInterviewEvaluationEntity> captor =
            ArgumentCaptor.forClass(VoiceInterviewEvaluationEntity.class);
        verify(evaluationRepository).save(captor.capture());
        assertThat(captor.getValue().getOverallScore()).isNull();
        assertThat(captor.getValue().getOverallFeedback()).contains("暂无可评估内容");
    }

    @Test
    @DisplayName("评估快照应冻结语音消息 ID 和已验证标签")
    void snapshotCarriesFrozenVoiceQuestionEvidenceSource() {
        LocalDateTime startedAt = LocalDateTime.now();
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(9L).skillId("java-backend").llmProvider("dashscope")
            .roleType("Java 后端开发").startTime(startedAt).build();
        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
            .id(501L).sessionId(9L).messageType("DIALOGUE")
            .aiGeneratedText("请解释 JMM 的 happens-before 规则。")
            .userRecognizedText("它定义了跨线程可见性顺序。")
            .taxonomyVersion(7)
            .primaryKnowledgeCode("TECH_KNOWLEDGE.JAVA.JMM")
            .secondaryKnowledgeCodesJson("[\"TECH_KNOWLEDGE.JAVA.CONCURRENCY\"]")
            .taggingStatus("VERIFIED")
            .build();
        when(sessionRepository.findById(9L)).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
            9L, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)).thenReturn(List.of(message));
        when(skillService.buildEvaluationReferenceSectionSafe("java-backend"))
            .thenReturn("reference");

        VoiceInterviewEvaluationService.EvaluationInput input = evaluationService.snapshot(9L);

        assertThat(input.questions()).hasSize(1);
        assertThat(input.evidenceSources()).containsExactly(
            new VoiceQuestionEvidenceSource(
                501L, 0, 7, "TECH_KNOWLEDGE.JAVA.JMM",
                List.of("TECH_KNOWLEDGE.JAVA.CONCURRENCY"), "VERIFIED"));
    }

    @Test
    @DisplayName("保存语音评估时应在同一完成动作中写入学习证据")
    void saveEvaluationPersistsLearningEvidence() {
        LocalDateTime startedAt = LocalDateTime.now();
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(9L).roleType("Java 后端开发").startTime(startedAt).build();
        EvaluationReport report = new EvaluationReport(
            "9", 1, 82, List.of(),
            List.of(new EvaluationReport.QuestionEvaluation(
                0, "解释 JMM", "技术问题", "可见性", 82, "清晰")),
            "overall", List.of("并发"), List.of(), List.of());
        List<VoiceQuestionEvidenceSource> sources = List.of(
            new VoiceQuestionEvidenceSource(
                501L, 0, 7, "TECH_KNOWLEDGE.JAVA.JMM", List.of(), "VERIFIED"));
        UUID taskId = UUID.randomUUID();
        when(evaluationRepository.findBySessionId(9L)).thenReturn(Optional.empty());
        when(learningEvidenceIngestionService.persistVoiceInterviewEvidence(
            eq("9"), eq(sources), eq(report), eq(taskId), eq(4L))).thenReturn(1);

        evaluationService.saveEvaluationTransactional(9L, session, report, sources, taskId, 4L);

        verify(evaluationRepository).save(any(VoiceInterviewEvaluationEntity.class));
        verify(learningEvidenceIngestionService).persistVoiceInterviewEvidence(
            "9", sources, report, taskId, 4L);
    }
}
