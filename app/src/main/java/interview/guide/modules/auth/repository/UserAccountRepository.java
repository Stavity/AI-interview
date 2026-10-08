package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.model.UserAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, Long> {
    Optional<UserAccountEntity> findByEmailNormalized(String emailNormalized);

    boolean existsByEmailNormalized(String emailNormalized);

    @Query("select account.id from UserAccountEntity account where account.status = :status order by account.id")
    List<Long> findIdsByStatus(@Param("status") UserAccountStatus status);
}
