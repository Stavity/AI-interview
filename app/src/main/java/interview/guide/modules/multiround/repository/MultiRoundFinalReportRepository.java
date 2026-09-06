package interview.guide.modules.multiround.repository;

import interview.guide.modules.multiround.model.MultiRoundFinalReportEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MultiRoundFinalReportRepository extends JpaRepository<MultiRoundFinalReportEntity, Long> {
  Optional<MultiRoundFinalReportEntity> findByInterviewId(Long interviewId);
}
