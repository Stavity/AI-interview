package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundQuestionGenerationServiceTest {
  @Mock private LlmProviderRegistry registry;
  @Mock private StructuredOutputInvoker outputInvoker;
  @Mock private PromptSanitizer promptSanitizer;
  @Mock private MultiRoundQuestionRepository questionRepository;

  @Test
  @DisplayName("AI 生成失败时降级为确定性题目")
  void shouldFallbackToDeterministicOnAiFailure() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setProviderId("provider-a");
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview);
    round.setRoundNo(1);
    round.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    round.setQuestionLimit(2);

    when(registry.getPlainChatClient("provider-a")).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "offline"));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(any()))
        .thenReturn(List.of())
        .thenReturn(java.util.List.of(createQuestion(1, "ALGORITHM"), createQuestion(2, "ALGORITHM"), createQuestion(3, "ALGORITHM")));
    when(questionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new MultiRoundQuestionGenerationService(registry, outputInvoker, promptSanitizer, questionRepository);
    // First call returns empty (no existing), then after save returns the saved list
    var result = service.ensureQuestionsGenerated(round);

    assertThat(result).hasSize(3);
    assertThat(result.get(0).getQuestionType()).isEqualTo("ALGORITHM");
    assertThat(result.get(0).getSequenceNo()).isEqualTo(1);
  }

  @Test
  @DisplayName("算法轮始终生成三道 Medium 算法题")
  void shouldGenerateThreeMediumAlgorithmQuestions() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setProviderId("provider-a");
    MultiRoundEntity round = new MultiRoundEntity();
    round.setId(11L);
    round.setInterview(interview);
    round.setRoundNo(1);
    round.setRoundType(MultiRoundEntity.Type.ALGORITHM);
    round.setQuestionLimit(1);
    when(registry.getPlainChatClient("provider-a"))
        .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "offline"));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(11L)).thenReturn(List.of());
    when(questionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    new MultiRoundQuestionGenerationService(registry, outputInvoker, promptSanitizer, questionRepository)
        .ensureQuestionsGenerated(round);

    ArgumentCaptor<MultiRoundQuestionEntity> saved = ArgumentCaptor.forClass(MultiRoundQuestionEntity.class);
    verify(questionRepository, org.mockito.Mockito.times(3)).save(saved.capture());
    assertThat(saved.getAllValues()).allSatisfy(question -> {
      assertThat(question.getQuestionType()).isEqualTo("ALGORITHM");
      assertThat(question.getDifficulty()).isEqualTo("medium");
    });
  }

  @Test
  @DisplayName("已有题目时不重复生成")
  void shouldNotRegenerateWhenQuestionsExist() {
    MultiRoundEntity round = new MultiRoundEntity();
    round.setId(10L);
    round.setQuestionLimit(2);
    MultiRoundQuestionEntity existing = new MultiRoundQuestionEntity();
    existing.setSequenceNo(1);
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(10L)).thenReturn(List.of(existing));

    var service = new MultiRoundQuestionGenerationService(registry, outputInvoker, promptSanitizer, questionRepository);
    var result = service.ensureQuestionsGenerated(round);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getSequenceNo()).isEqualTo(1);
  }
  private MultiRoundQuestionEntity createQuestion(int seq, String type) {
    MultiRoundQuestionEntity q = new MultiRoundQuestionEntity();
    q.setSequenceNo(seq);
    q.setQuestionType(type);
    return q;
  }

}
