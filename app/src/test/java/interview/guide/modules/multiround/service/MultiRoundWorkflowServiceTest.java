package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.multiround.model.MultiRoundEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.model.MultiRoundWorkflowEventEntity;
import interview.guide.modules.multiround.repository.MultiRoundRepository;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowEventRepository;
import interview.guide.modules.multiround.repository.MultiRoundWorkflowInstanceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundWorkflowServiceTest {
  @Mock private MultiRoundWorkflowInstanceRepository workflowRepository;
  @Mock private MultiRoundWorkflowEventRepository eventRepository;
  @Mock private MultiRoundRepository roundRepository;

  private MultiRoundWorkflowService service() {
    return new MultiRoundWorkflowService(workflowRepository, eventRepository, roundRepository, new ObjectMapper());
  }

  @Test
  @DisplayName("静态 workflow 初始化会持久化定义、稳定节点元数据和创建事件")
  void shouldInitializeDurableStaticWorkflow() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(7L);
    MultiRoundEntity algorithm = node(interview, 1, MultiRoundEntity.Type.ALGORITHM);
    MultiRoundEntity technical = node(interview, 2, MultiRoundEntity.Type.TECHNICAL_1);
    when(workflowRepository.findByInterviewId(7L)).thenReturn(Optional.empty());
    when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var workflow = service().initializeStatic(interview, List.of(technical, algorithm));

    assertThat(workflow.getDefinitionVersion()).isEqualTo(MultiRoundWorkflowService.STATIC_DEFINITION_VERSION);
    assertThat(workflow.getCheckpointKey()).isEqualTo("NODE_static-01-algorithm_READY");
    assertThat(algorithm.getNodeKey()).isEqualTo("static-01-algorithm");
    assertThat(technical.getDisplayOrder()).isEqualTo(2);
    assertThat(interview.getOrchestrationMode()).isEqualTo(MultiRoundInterviewEntity.OrchestrationMode.LEGACY_STATIC);

    ArgumentCaptor<MultiRoundWorkflowEventEntity> event = ArgumentCaptor.forClass(MultiRoundWorkflowEventEntity.class);
    verify(eventRepository).save(event.capture());
    assertThat(event.getValue().getEventType()).isEqualTo("WORKFLOW_CREATED");
    assertThat(event.getValue().getEventKey()).isEqualTo("workflow-created");
  }

  @Test
  @DisplayName("节点开始会更新持久化 checkpoint、revision 并追加事件")
  void shouldAppendNodeStartedEvent() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    interview.setId(8L);
    MultiRoundEntity node = node(interview, 1, MultiRoundEntity.Type.ALGORITHM);
    node.setNodeKey("static-01-algorithm");
    var workflow = new interview.guide.modules.multiround.model.MultiRoundWorkflowInstanceEntity();
    workflow.setWorkflowRevision(3);
    when(workflowRepository.findByInterviewId(8L)).thenReturn(Optional.of(workflow));

    service().nodeStarted(interview, node);

    assertThat(workflow.getWorkflowRevision()).isEqualTo(4);
    assertThat(workflow.getCheckpointKey()).isEqualTo("NODE_static-01-algorithm_IN_PROGRESS");
    verify(eventRepository).save(any(MultiRoundWorkflowEventEntity.class));
  }

  private MultiRoundEntity node(MultiRoundInterviewEntity interview, int roundNo, MultiRoundEntity.Type type) {
    MultiRoundEntity node = new MultiRoundEntity();
    node.setInterview(interview);
    node.setRoundNo(roundNo);
    node.setRoundType(type);
    node.setQuestionLimit(3);
    return node;
  }
}
