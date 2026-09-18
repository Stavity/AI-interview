package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.multiround.model.MultiRoundIdempotencyEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundIdempotencyRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiRoundIdempotencyServiceTest {
  @Mock private MultiRoundIdempotencyRepository repository;
  @Test @DisplayName("相同会话、操作和请求键返回已保存的结果")
  void shouldReuseExistingResult() {
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setId(9L);
    MultiRoundIdempotencyEntity record = new MultiRoundIdempotencyEntity(); record.setInterview(interview);
    when(repository.findBySessionIdAndOperationTypeAndIdempotencyKey("s-1", "CREATE", "key-1")).thenReturn(Optional.of(record));
    assertThat(new MultiRoundIdempotencyService(repository).findCompleted("s-1", "CREATE", "key-1")).containsSame(interview);
  }
  @Test @DisplayName("首次操作保存请求键与结果")
  void shouldPersistResultForFirstOperation() {
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity(); interview.setId(9L);

    var saved = new MultiRoundIdempotencyService(repository).record("s-1", "CREATE", "key-1", interview);

    // 原测试只断言「查另一个 key 为空」——record() 即使是空实现也能通过。
    // 这里直接校验落库内容。
    var captor = ArgumentCaptor.forClass(MultiRoundIdempotencyEntity.class);
    verify(repository).save(captor.capture());
    MultiRoundIdempotencyEntity persisted = captor.getValue();
    assertThat(persisted.getSessionId()).isEqualTo("s-1");
    assertThat(persisted.getOperationType()).isEqualTo("CREATE");
    assertThat(persisted.getIdempotencyKey()).isEqualTo("key-1");
    assertThat(persisted.getInterview()).isSameAs(interview);
    assertThat(saved).isSameAs(persisted);
  }

  @Test @DisplayName("未记录过的请求键查不到结果")
  void shouldNotFindUnknownKey() {
    when(repository.findBySessionIdAndOperationTypeAndIdempotencyKey("s-1", "CREATE", "other"))
        .thenReturn(Optional.empty());
    assertThat(new MultiRoundIdempotencyService(repository).find("s-1", "CREATE", "other")).isEmpty();
  }
  @Test @DisplayName("处理结果可指向首次创建的领域资源")
  void shouldPersistResourceReference() {
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MultiRoundInterviewEntity interview = new MultiRoundInterviewEntity();
    MultiRoundIdempotencyEntity saved = new MultiRoundIdempotencyService(repository)
        .record("s-1", "SUBMIT", "key-2", interview, "ALGORITHM_SUBMISSION", 33L);
    assertThat(saved.getResourceType()).isEqualTo("ALGORITHM_SUBMISSION");
    assertThat(saved.getResourceId()).isEqualTo(33L);
  }
}
