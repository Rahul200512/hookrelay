package io.github.rahul200512.hookrelay.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    long countByTenantIdAndRevokedAtIsNull(UUID tenantId);
}
