package interview.guide.modules.learning.service;

import interview.guide.modules.learning.model.LearningProgressEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningProgressRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningProgressServiceTest {

  @Mock
  private LearningProgressRepository progressRepository;

  @Test
  @DisplayName("掌握度增量限制在 100")
  void shouldClampMasteryScoreAtOneHundred() {
    LearningSkillSourceEntity source = LearningSkillSourceEntity.active("java", "JVM", "JVM", "", "a".repeat(64));
    LearningProgressEntity existing = LearningProgressEntity.create(source);
    existing.setMasteryScore(95);
    when(progressRepository.findBySkillSource(source)).thenReturn(Optional.of(existing));
    when(progressRepository.save(any(LearningProgressEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    LearningProgressEntity updated = new LearningProgressService(progressRepository).adjustMastery(source, 12);

    assertThat(updated.getMasteryScore()).isEqualTo(100);
    verify(progressRepository).save(existing);
  }

  @Test
  @DisplayName("首次记录和负向增量限制在 0")
  void shouldCreateProgressAndClampMasteryScoreAtZero() {
    LearningSkillSourceEntity source = LearningSkillSourceEntity.active("java", "JVM", "JVM", "", "a".repeat(64));
    when(progressRepository.findBySkillSource(source)).thenReturn(Optional.empty());
    when(progressRepository.save(any(LearningProgressEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    LearningProgressEntity updated = new LearningProgressService(progressRepository).adjustMastery(source, -8);

    assertThat(updated.getMasteryScore()).isZero();
    assertThat(updated.getStatus()).isEqualTo(LearningProgressEntity.Status.NOT_STARTED);
    ArgumentCaptor<LearningProgressEntity> captor = ArgumentCaptor.forClass(LearningProgressEntity.class);
    verify(progressRepository).save(captor.capture());
    assertThat(captor.getValue().getSkillSource()).isSameAs(source);
  }

  @Test
  @DisplayName("非零且未满分的掌握度状态为 LEARNING")
  void shouldUseLearningStatusForPartialMastery() {
    LearningSkillSourceEntity source = LearningSkillSourceEntity.active("java", "JVM", "JVM", "", "a".repeat(64));
    when(progressRepository.findBySkillSource(source)).thenReturn(Optional.empty());
    when(progressRepository.save(any(LearningProgressEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    LearningProgressEntity updated = new LearningProgressService(progressRepository).adjustMastery(source, 10);
    assertThat(updated.getStatus().name()).isEqualTo("LEARNING");
  }
}
