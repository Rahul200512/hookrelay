package io.github.rahul200512.hookrelay.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    List<Endpoint> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Endpoint> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);
}
