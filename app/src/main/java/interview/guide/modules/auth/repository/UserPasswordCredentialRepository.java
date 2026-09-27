package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserPasswordCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPasswordCredentialRepository extends JpaRepository<UserPasswordCredentialEntity, Long> {
    Optional<UserPasswordCredentialEntity> findByUserId(Long userId);
}
