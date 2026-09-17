package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateTaskSubmission;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音评估任务恢复")
class VoiceInterviewEvaluationRecoveryTest {

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private VoiceInterviewEvaluationRepository evaluationRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private VoiceInterviewProperties properties;

    @Mock
    private VoiceEvaluateTaskSubmission voiceEvaluateTaskSubmission;

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private RBucket<VoiceInterviewSessionEntity> sessionBucket;

    private VoiceInterviewService voiceInterviewService;

    @BeforeEach
    void setUp() {
        voiceInterviewService = new VoiceInterviewService(
            sessionRepository,
            messageRepository,
            evaluationRepository,
            redissonClient,
            properties,
            voiceEvaluateTaskSubmission,
            llmProviderRegistry
        );
        lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString()))
            .thenReturn(sessionBucket);
    }

    @Test
    @DisplayName("WebSocket 异常断开结束会话后也应投递评估任务")
    void shouldEnqueueEvaluationAfterDisconnectEndsSession() {
        VoiceInterviewSessionEntity session = inProgressSession(1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        voiceInterviewService.endSessionIfInProgress("1");

        assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.COMPLETED);
        assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
        verify(voiceEvaluateTaskSubmission).sendEvaluateTask("1");
    }

    @Test
    @DisplayName("定时结束超时会话后应投递评估任务")
    void shouldEnqueueEvaluationAfterCleaningStaleInterview() {
        VoiceInterviewSessionEntity session = inProgressSession(2L);
        when(sessionRepository.findByStatusAndStartTimeBefore(
            eq(VoiceInterviewSessionStatus.IN_PROGRESS),
            any(LocalDateTime.class)
        )).thenReturn(List.of(session));

        int cleaned = voiceInterviewService.cleanupStaleSessions();

        assertThat(cleaned).isEqualTo(1);
        verify(voiceEvaluateTaskSubmission).sendEvaluateTask("2");
    }



    @Test
    @DisplayName("评估状态更新后应清除会话缓存")
    void shouldInvalidateSessionCacheAfterEvaluationStatusChanges() {
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(4L)
            .evaluateStatus(AsyncTaskStatus.PENDING)
            .build();
        when(sessionRepository.findById(4L)).thenReturn(Optional.of(session));

        voiceInterviewService.updateEvaluateStatus(4L, AsyncTaskStatus.COMPLETED, null);

        assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        verify(sessionBucket).delete();
    }



    private VoiceInterviewSessionEntity inProgressSession(Long sessionId) {
        return VoiceInterviewSessionEntity.builder()
            .id(sessionId)
            .status(VoiceInterviewSessionStatus.IN_PROGRESS)
            .startTime(LocalDateTime.now().minusMinutes(5))
            .build();
    }

}
