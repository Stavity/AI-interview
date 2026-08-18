package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundQuestionEntity;
import interview.guide.modules.multiround.repository.MultiRoundQuestionRepository;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundQuestionServiceTest {
  @Mock private MultiRoundRepository roundRepository;
  @Mock private MultiRoundQuestionRepository questionRepository;

  @Test @DisplayName("未启动轮次不能通过新增题目绕过状态机")
  void shouldRejectQuestionBeforeRoundStarted() {
    MultiRoundEntity round = new MultiRoundEntity(); round.setQuestionLimit(2);
    when(roundRepository.findById(3L)).thenReturn(Optional.of(round));

    assertThatThrownBy(() -> new MultiRoundQuestionService(roundRepository, questionRepository).addQuestion(3L, "ALGORITHM", "数组", "两数之和", "哈希表"))
        .hasMessageContaining("轮次尚未启动");
  }

  @Test @DisplayName("已启动轮次按服务端序号保存题目")
  void shouldAddQuestionForStartedRound() {
    MultiRoundEntity round = new MultiRoundEntity(); round.setQuestionLimit(2); round.setStatus(MultiRoundEntity.Status.IN_PROGRESS);
    when(roundRepository.findById(3L)).thenReturn(Optional.of(round));
    when(questionRepository.findByRoundIdOrderBySequenceNoAsc(3L)).thenReturn(List.of());
    when(questionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MultiRoundQuestionEntity question = new MultiRoundQuestionService(roundRepository, questionRepository).addQuestion(3L, "ALGORITHM", "数组", "两数之和", "哈希表");
    assertThat(question.getSequenceNo()).isEqualTo(1);
  }

  @Test @DisplayName("提交回答后保持轮次进行中，必须由显式结束操作生成报告")
  void shouldKeepRoundInProgressUntilExplicitFinish() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundEntity round = new MultiRoundEntity(); round.setQuestionLimit(1); round.setStatus(MultiRoundEntity.Status.IN_PROGRESS); round.setRoundNo(1); round.setInterview(interview);
    MultiRoundQuestionEntity question = new MultiRoundQuestionEntity(); question.setRound(round);
    when(questionRepository.findById(4L)).thenReturn(Optional.of(question));
    new MultiRoundQuestionService(roundRepository, questionRepository).answer(4L, "用哈希表记录差值");
    assertThat(question.getStatus()).isEqualTo(MultiRoundQuestionEntity.Status.ANSWERED);
    assertThat(round.getStatus()).isEqualTo(MultiRoundEntity.Status.IN_PROGRESS);
    assertThat(interview.getCurrentRoundNo()).isEqualTo(0);
  }
}
