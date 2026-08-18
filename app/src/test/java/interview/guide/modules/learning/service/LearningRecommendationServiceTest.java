package interview.guide.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.learning.model.LearningProgressEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningProgressRepository;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LearningRecommendationServiceTest {
  @Mock private LearningSkillSourceRepository sourceRepository;
  @Mock private LearningProgressRepository progressRepository;

  @Test @DisplayName("学习路径优先推荐掌握度最低的有效技能")
  void shouldPrioritizeLowestMasteryActiveSources() {
    LearningSkillSourceEntity lower = source("算法", 1L);
    LearningSkillSourceEntity higher = source("数据库", 2L);
    LearningProgressEntity progress = LearningProgressEntity.create(higher); progress.adjustMastery(80);
    when(sourceRepository.findAll()).thenReturn(List.of(higher, lower));
    when(progressRepository.findBySkillSource(lower)).thenReturn(Optional.empty());
    when(progressRepository.findBySkillSource(higher)).thenReturn(Optional.of(progress));

    var result = new LearningRecommendationService(sourceRepository, progressRepository).recommend();

    assertThat(result).extracting(LearningRecommendationService.Recommendation::sourceId).containsExactly(1L, 2L);
    assertThat(result.getFirst().reason()).contains("当前掌握度 0");
  }

  private LearningSkillSourceEntity source(String name, Long id) {
    LearningSkillSourceEntity value = LearningSkillSourceEntity.active("java", name, name, "description", "a".repeat(64));
    value.setId(id);
    return value;
  }
}
