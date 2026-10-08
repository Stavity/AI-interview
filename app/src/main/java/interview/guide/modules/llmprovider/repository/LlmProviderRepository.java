package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {

  List<LlmProviderEntity> findByEnabledTrueOrderByIdAsc();

  @Query("SELECT p FROM LlmProviderEntity p WHERE p.builtin = true OR p.ownerUserId = :ownerUserId ORDER BY p.id")
  List<LlmProviderEntity> findAccessibleToUser(@Param("ownerUserId") Long ownerUserId);
}
