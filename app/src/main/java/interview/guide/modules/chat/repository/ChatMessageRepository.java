package interview.guide.modules.chat.repository;

import interview.guide.modules.chat.model.ChatMessageEntity;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
  List<ChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId);
  Page<ChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId, Pageable pageable);
  void deleteBySessionId(Long sessionId);
}
