package interview.guide.modules.learning.service;

import interview.guide.modules.learning.model.LearningProgressEntity;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningProgressService {

  private final LearningProgressRepository progressRepository;

  @Transactional
  public LearningProgressEntity adjustMastery(LearningSkillSourceEntity source, int delta) {
    LearningProgressEntity progress = progressRepository.findBySkillSource(source)
        .orElseGet(() -> LearningProgressEntity.create(source));
    progress.adjustMastery(delta);
    return progressRepository.save(progress);
  }
}
