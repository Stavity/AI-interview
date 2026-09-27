package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.AuthActionTokenEntity;
import interview.guide.modules.auth.model.AuthTokenPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthActionTokenRepository extends JpaRepository<AuthActionTokenEntity, Long> {
    @Modifying
    @Query("update AuthActionTokenEntity t set t.consumedAt = :now " +
            "where t.user.id = :userId and t.purpose = :purpose and t.consumedAt is null")
    int consumeAllActiveForUser(@Param("userId") Long userId,
                                @Param("purpose") AuthTokenPurpose purpose,
                                @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AuthActionTokenEntity t join fetch t.user " +
            "where t.tokenHash = :tokenHash and t.purpose = :purpose " +
            "and t.consumedAt is null and t.expiresAt > :now")
    Optional<AuthActionTokenEntity> findUsableForUpdate(@Param("tokenHash") String tokenHash,
                                                        @Param("purpose") AuthTokenPurpose purpose,
                                                        @Param("now") LocalDateTime now);
}
