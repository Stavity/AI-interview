package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateTaskSubmission;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import tools.jackson.databind.ObjectMapper;

class VoiceQuestionSnapshotPersistenceTest {

  @Test
  void persistsVerifiedTagSnapshotTogetherWithGeneratedQuestion() {
    VoiceInterviewSessionRepository sessions = mock(VoiceInterviewSessionRepository.class);
    VoiceInterviewMessageRepository messages = mock(VoiceInterviewMessageRepository.class);
    RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    RBucket<VoiceInterviewSessionEntity> bucket = mock(RBucket.class);
    when(redisson.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
    when(bucket.get()).thenReturn(null);
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(7L).currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH).build();
    when(sessions.findById(7L)).thenReturn(Optional.of(session));
    when(messages.findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(7L))
        .thenReturn(Optional.empty());
    when(messages.countBySessionIdAndMessageTypeNot(
        7L, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)).thenReturn(2L);

    VoiceInterviewService service = new VoiceInterviewService(
        sessions,
        messages,
        mock(VoiceInterviewEvaluationRepository.class),
        redisson,
        new VoiceInterviewProperties(),
        mock(VoiceEvaluateTaskSubmission.class),
        mock(LlmProviderRegistry.class),
        new ObjectMapper());
    VoiceQuestionTagSnapshot snapshot = new VoiceQuestionTagSnapshot(
        4, "SCENARIO", "TECH_KNOWLEDGE.JAVA.CONCURRENCY",
        List.of("TECH_KNOWLEDGE.JAVA.JMM"), 7, "VERIFIED");

    service.saveMessage("7", "使用 jstack 排查", "请继续说明如何确认死锁。", snapshot);

    ArgumentCaptor<VoiceInterviewMessageEntity> captor =
        ArgumentCaptor.forClass(VoiceInterviewMessageEntity.class);
    verify(messages).save(captor.capture());
    VoiceInterviewMessageEntity saved = captor.getValue();
    assertThat(saved.getSequenceNum()).isEqualTo(3);
    assertThat(saved.getQuestionDifficultyLevel()).isEqualTo(4);
    assertThat(saved.getQuestionType()).isEqualTo("SCENARIO");
    assertThat(saved.getPrimaryKnowledgeCode()).isEqualTo("TECH_KNOWLEDGE.JAVA.CONCURRENCY");
    assertThat(saved.getSecondaryKnowledgeCodesJson())
        .isEqualTo("[\"TECH_KNOWLEDGE.JAVA.JMM\"]");
    assertThat(saved.getTaxonomyVersion()).isEqualTo(7);
    assertThat(saved.getTaggingStatus()).isEqualTo("VERIFIED");
  }
}
