package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.agent.InterviewPlan;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import java.util.List;
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

  private MultiRoundQuestionGenerationService service() {
    return new MultiRoundQuestionGenerationService(registry, outputInvoker, promptSanitizer, questionRepository);
  }

  private MultiRoundEntity round(MultiRoundEntity.Type type, int limit) {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setProviderId("provider-a");
    MultiRoundEntity round = new MultiRoundEntity();
    round.setInterview(interview);
    round.setRoundNo(1);
    round.setRoundType(type);
    round.setQuestionLimit(limit);
    return round;
  }

  private InterviewPlan plan(List<String> categoryKeys) {
    return new InterviewPlan(1, "ALGORITHM", 0, categoryKeys, 1);
  }

  /** 让 LLM 返回指定题目列表 */
  private void stubLlm(List<MultiRoundQuestionGenerationService.GeneratedQuestion> questions) {
    lenient().when(registry.getPlainChatClient(anyString())).thenReturn(null);
    lenient().when(promptSanitizer.wrapWithDelimiters(anyString(), anyString())).thenReturn("prompt");
    lenient().when(outputInvoker.invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new MultiRoundQuestionGenerationService.GeneratedQuestionList(questions));
  }

  @Test
  @DisplayName("AI 生成失败时降级为确定性题目，且数量补齐到上限")
  void shouldFallbackToDeterministicOnAiFailure() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 2);
    when(registry.getPlainChatClient("provider-a"))
        .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "offline"));

    var drafts = service().draft(round, plan(List.of("resume-project")), null, 2);

    assertThat(drafts).hasSize(2);
    assertThat(drafts).allSatisfy(draft -> {
      assertThat(draft.questionType()).isEqualTo("ALGORITHM");
      assertThat(draft.question()).isNotBlank();
      assertThat(draft.categoryKey()).isEqualTo("resume-project");
    });
  }

  @Test
  @DisplayName("LLM 返回空列表时同样降级，不会留下 0 题的轮次")
  void shouldFallbackWhenLlmReturnsEmptyList() {
    // 旧实现空列表不抛异常、不触发降级，轮次留 0 题后 finish() 永远失败
    MultiRoundEntity round = round(MultiRoundEntity.Type.TECHNICAL_1, 3);
    stubLlm(List.of());

    var drafts = service().draft(round, plan(List.of("resume-project")), null, 3);

    assertThat(drafts).hasSize(3);
    assertThat(drafts).allSatisfy(d -> assertThat(d.question()).isNotBlank());
  }

  @Test
  @DisplayName("LLM 返回数量不足时用确定性题目补齐")
  void shouldTopUpWhenLlmReturnsTooFew() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.TECHNICAL_1, 3);
    stubLlm(List.of(new MultiRoundQuestionGenerationService.GeneratedQuestion(
        "TECHNICAL", "resume-project", "来自 LLM 的题目", "参考答案")));

    var drafts = service().draft(round, plan(List.of("resume-project")), null, 3);

    assertThat(drafts).hasSize(3);
    assertThat(drafts.getFirst().question()).isEqualTo("来自 LLM 的题目");
  }

  @Test
  @DisplayName("questionType 超长或不在词表内时回退到轮次默认类型，不会写超 VARCHAR(32)")
  void shouldClampOversizedQuestionType() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 1);
    stubLlm(List.of(new MultiRoundQuestionGenerationService.GeneratedQuestion(
        "ALGORITHM_DATA_STRUCTURE_AND_COMPLEXITY_ANALYSIS", "resume-project", "题目", "答案")));

    var drafts = service().draft(round, plan(List.of("resume-project")), null, 1);

    assertThat(drafts).hasSize(1);
    assertThat(drafts.getFirst().questionType()).isEqualTo("ALGORITHM");
    assertThat(drafts.getFirst().questionType().length()).isLessThanOrEqualTo(32);
  }

  @Test
  @DisplayName("categoryKey 不在计划允许分类内时回退，不会写超 VARCHAR(128)")
  void shouldClampInvalidCategoryKey() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 1);
    stubLlm(List.of(new MultiRoundQuestionGenerationService.GeneratedQuestion(
        "ALGORITHM", "x".repeat(200), "题目", "答案")));

    var drafts = service().draft(round, plan(List.of("resume-project")), null, 1);

    assertThat(drafts.getFirst().categoryKey()).isEqualTo("resume-project");
    assertThat(drafts.getFirst().categoryKey().length()).isLessThanOrEqualTo(128);
  }

  @Test
  @DisplayName("计划分类为空时不抛异常，回退到轮次类型")
  void shouldSurviveEmptyPlanCategoryKeys() {
    // 旧实现 plan.categoryKeys().getFirst() 在 try 内抛 NoSuchElementException，
    // 降级分支里再抛一次并逃出 catch
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 2);
    stubLlm(List.of());

    var drafts = service().draft(round, plan(List.of()), null, 2);

    assertThat(drafts).hasSize(2);
    assertThat(drafts).allSatisfy(d -> assertThat(d.categoryKey()).isEqualTo("ALGORITHM"));
  }

  @Test
  @DisplayName("LLM 返回空白 question 的条目被丢弃并补齐，避免违反 NOT NULL")
  void shouldSkipBlankQuestionText() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 2);
    stubLlm(List.of(
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "  ", "答案"),
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "有效题目", "答案")));

    var drafts = service().draft(round, plan(List.of("resume-project")), null, 2);

    assertThat(drafts).hasSize(2);
    assertThat(drafts).allSatisfy(d -> assertThat(d.question()).isNotBlank());
    assertThat(drafts.getFirst().question()).isEqualTo("有效题目");
  }

  @Test
  @DisplayName("题目已满额时不重复生成")
  void shouldNotRegenerateWhenQuestionsExist() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 2);
    round.setId(10L);
    MultiRoundQuestionEntity first = new MultiRoundQuestionEntity();
    first.setSequenceNo(1);
    MultiRoundQuestionEntity second = new MultiRoundQuestionEntity();
    second.setSequenceNo(2);
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(10L)).thenReturn(List.of(first, second));

    var result = service().ensureQuestionsGenerated(round);

    assertThat(result).hasSize(2);
    verify(questionRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("persist 单批写入且 sequence_no 从已有数量之后接续")
  void shouldPersistWithContinuedSequenceNo() {
    MultiRoundEntity round = round(MultiRoundEntity.Type.ALGORITHM, 3);
    when(questionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service().persist(round, plan(List.of("resume-project")), List.of(
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "q2", "a2"),
        new MultiRoundQuestionGenerationService.GeneratedQuestion("ALGORITHM", "resume-project", "q3", "a3")), 1);

    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(questionRepository).saveAll(captor.capture());
    List<MultiRoundQuestionEntity> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved.get(0).getSequenceNo()).isEqualTo(2);
    assertThat(saved.get(1).getSequenceNo()).isEqualTo(3);
  }
}
