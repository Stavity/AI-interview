package interview.guide.modules.multiround.service;

import interview.guide.modules.multiround.model.MultiRoundIdempotencyEntity;
import interview.guide.modules.multiround.model.MultiRoundInterviewEntity;
import interview.guide.modules.multiround.repository.MultiRoundIdempotencyRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class MultiRoundIdempotencyService {
  private final MultiRoundIdempotencyRepository repository;
  @Transactional(readOnly = true)
  public Optional<MultiRoundInterviewEntity> findCompleted(String sessionId, String operation, String key) { return repository.findBySessionIdAndOperationTypeAndIdempotencyKey(sessionId, operation, key).map(MultiRoundIdempotencyEntity::getInterview); }
  @Transactional(readOnly = true)
  public Optional<MultiRoundIdempotencyEntity> find(String sessionId, String operation, String key) {
    return repository.findBySessionIdAndOperationTypeAndIdempotencyKey(sessionId, operation, key);
  }
  @Transactional
  public MultiRoundIdempotencyEntity record(String sessionId, String operation, String key, MultiRoundInterviewEntity interview) {
    return record(sessionId, operation, key, interview, null, null);
  }
  @Transactional
  public MultiRoundIdempotencyEntity record(String sessionId, String operation, String key, MultiRoundInterviewEntity interview, String resourceType, Long resourceId) {
    MultiRoundIdempotencyEntity value = new MultiRoundIdempotencyEntity();
    value.setSessionId(sessionId); value.setOperationType(operation); value.setIdempotencyKey(key); value.setInterview(interview);
    value.setResourceType(resourceType); value.setResourceId(resourceId);
    return repository.save(value);
  }
}
