package interview.guide.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.tenant.TenantContext;
import interview.guide.modules.learning.model.LearningAssessmentEvidenceEntity;
import interview.guide.modules.learning.repository.LearningAssessmentEvidenceRepository;
import interview.guide.modules.learning.repository.LearningKnowledgeProgressRepository;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.NodeProgress;
import interview.guide.modules.learning.service.LearningEvidenceAggregator.ProgressStatus;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionTaxonomyVersionEntity;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.repository.QuestionTaxonomyVersionRepository;
import interview.guide.modules.questiontag.service.QuestionTagQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LearningKnowledgeProgressServiceTest {

  @Mock private LearningAssessmentEvidenceRepository evidenceRepository;
  @Mock private LearningKnowledgeProgressRepository progressRepository;
  @Mock private QuestionKnowledgeNodeRepository nodeRepository;
  @Mock private QuestionTaxonomyVersionRepository versionRepository;
  @Mock private QuestionTagQueryService taxonomyVersionResolver;
  @Mock private LearningEvidenceAggregator aggregator;
  @Mock private LearningProperties properties;
  @InjectMocks private LearningKnowledgeProgressService service;

  @Test
  void rebuildsOnlyCurrentOwnerAndReplacesVersionSnapshot() {
    QuestionKnowledgeNodeEntity node = QuestionKnowledgeNodeEntity.create(
        3, "ROOT.JAVA", "Java", null, "CONCEPT", null, true, 1);
    LearningAssessmentEvidenceEntity evidence = mock(LearningAssessmentEvidenceEntity.class);
    when(evidence.getOwnerUserId()).thenReturn(7L);
    when(evidence.getTaxonomyVersion()).thenReturn(3);
    when(evidence.getSourceType()).thenReturn("TEXT_INTERVIEW");
    when(evidence.getSourceSessionId()).thenReturn("session-1");
    when(evidence.getQuestionIndex()).thenReturn(0);
    when(evidence.getNodeCode()).thenReturn("ROOT.JAVA");
    when(evidence.getTagRole()).thenReturn("PRIMARY");
    when(evidence.getScore()).thenReturn(80);
    when(evidence.isVerified()).thenReturn(true);
    when(evidence.getAssessedAt()).thenReturn(LocalDateTime.now());
    when(evidence.getEvaluationFingerprint()).thenReturn("fingerprint");
    when(evidence.getId()).thenReturn(10L);
    when(versionRepository.findById(3)).thenReturn(Optional.of(mock(QuestionTaxonomyVersionEntity.class)));
    when(nodeRepository.findByTaxonomyVersionAndStatusOrderBySortOrderAscCodeAsc(3,
        QuestionKnowledgeNodeEntity.Status.ACTIVE)).thenReturn(List.of(node));
    when(evidenceRepository.findByOwnerUserIdAndTaxonomyVersionOrderByAssessedAtDescIdDesc(7L, 3))
        .thenReturn(List.of(evidence));
    when(aggregator.aggregate(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(3),
        anyList(), anyList(), org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
        .thenReturn(List.of(new NodeProgress(
            "ROOT.JAVA", null, 80, 1, 1, 1, 0, 33,
            ProgressStatus.LEARNING, LocalDateTime.now())));
    when(properties.getAggregationRuleVersion()).thenReturn(1);

    TenantContext.runAs(7L, () -> service.rebuild(7L, 3));

    verify(progressRepository).deleteByOwnerUserIdAndTaxonomyVersion(7L, 3);
    verify(progressRepository).flush();
    verify(progressRepository).saveAll(anyList());
  }

  @Test
  void rejectsCrossOwnerRebuildEvenForInternalEntryPoint() {
    assertThatThrownBy(() -> TenantContext.runAs(7L, () -> service.rebuild(8L, 3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("当前认证用户");
  }
}
