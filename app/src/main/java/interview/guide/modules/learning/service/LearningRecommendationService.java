package interview.guide.modules.learning.service;

import interview.guide.modules.learning.model.LearningProgressEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningProgressRepository;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningRecommendationService {
  private final LearningSkillSourceRepository sourceRepository;
  private final LearningProgressRepository progressRepository;

  @Transactional(readOnly = true)
  public List<Recommendation> recommend() {
    return sourceRepository.findAll().stream()
        .filter(source -> source.getStatus() == LearningSkillSourceEntity.Status.ACTIVE)
        .map(source -> {
          int mastery = progressRepository.findBySkillSource(source).map(LearningProgressEntity::getMasteryScore).orElse(0);
          return new Recommendation(source.getId(), source.getSkillId(), source.getCategoryKey(), source.getDisplayName(), mastery,
              "当前掌握度 " + mastery + "，建议优先安排学习任务。");
        })
        .sorted(Comparator.comparingInt(Recommendation::masteryScore).thenComparing(Recommendation::displayName))
        .limit(12)
        .toList();
  }

  public record Recommendation(Long sourceId, String skillId, String categoryKey, String displayName, int masteryScore, String reason) {}
}
