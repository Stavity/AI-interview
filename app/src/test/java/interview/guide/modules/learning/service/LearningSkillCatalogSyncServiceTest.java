package interview.guide.modules.learning.service;

import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningSkillCatalogSyncServiceTest {

  @Mock
  private InterviewSkillService interviewSkillService;

  @Mock
  private LearningSkillSourceRepository sourceRepository;

  @Test
  @DisplayName("首次同步为每个 Skill 分类创建 ACTIVE 来源")
  void shouldCreateActiveSourcesForSkillCategories() {
    when(interviewSkillService.getAllSkills()).thenReturn(List.of(skill("java-backend", "Java 后端", "JVM")));
    when(sourceRepository.findAll()).thenReturn(List.of());
    when(sourceRepository.save(any(LearningSkillSourceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LearningSkillCatalogSyncService service = new LearningSkillCatalogSyncService(
        interviewSkillService, sourceRepository
    );

    service.syncCatalog();

    ArgumentCaptor<LearningSkillSourceEntity> captor = ArgumentCaptor.forClass(LearningSkillSourceEntity.class);
    verify(sourceRepository).save(captor.capture());
    LearningSkillSourceEntity saved = captor.getValue();
    assertThat(saved.getSkillId()).isEqualTo("java-backend");
    assertThat(saved.getCategoryKey()).isEqualTo("JVM");
    assertThat(saved.getStatus()).isEqualTo(LearningSkillSourceEntity.Status.ACTIVE);
    assertThat(saved.getContentHash()).hasSize(64);
  }

  @Test
  @DisplayName("内容未变化的来源不会重复保存")
  void shouldNotSaveUnchangedSource() {
    InterviewSkillService.SkillDTO skill = skill("java-backend", "Java 后端", "JVM");
    LearningSkillSourceEntity existing = LearningSkillSourceEntity.active(
        "java-backend", "JVM", "JVM", "Java 后端 / JVM\nSkill 描述",
        LearningSkillCatalogSyncService.contentHash(skill, skill.categories().getFirst())
    );
    when(interviewSkillService.getAllSkills()).thenReturn(List.of(skill));
    when(sourceRepository.findAll()).thenReturn(List.of(existing));

    new LearningSkillCatalogSyncService(interviewSkillService, sourceRepository).syncCatalog();

    verify(sourceRepository, never()).save(any(LearningSkillSourceEntity.class));
  }

  @Test
  @DisplayName("本轮未发现的来源标记为 UNAVAILABLE")
  void shouldMarkMissingSourceUnavailable() {
    LearningSkillSourceEntity missing = LearningSkillSourceEntity.active(
        "legacy", "OLD", "旧分类", "旧 Skill", "a".repeat(64)
    );
    when(interviewSkillService.getAllSkills()).thenReturn(List.of());
    when(sourceRepository.findAll()).thenReturn(List.of(missing));
    when(sourceRepository.save(any(LearningSkillSourceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    new LearningSkillCatalogSyncService(interviewSkillService, sourceRepository).syncCatalog();

    assertThat(missing.getStatus()).isEqualTo(LearningSkillSourceEntity.Status.UNAVAILABLE);
    verify(sourceRepository).save(missing);
  }

  private InterviewSkillService.SkillDTO skill(String id, String name, String categoryKey) {
    return new InterviewSkillService.SkillDTO(
        id,
        name,
        "Skill 描述",
        List.of(new InterviewSkillService.SkillCategoryDTO(categoryKey, categoryKey, "CORE", "java.md", false)),
        true,
        null,
        "persona",
        null
    );
  }
}
