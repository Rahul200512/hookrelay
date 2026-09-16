package io.github.rahul200512.hookrelay.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    List<Endpoint> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Endpoint> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    /**
     * Endpoints that auto-paused long enough ago to be worth another try. An endpoint a
     * user disabled, or one that answered 410, has {@code enabled = false} and is never
     * picked up here: only the service's own pauses are lifted automatically.
     */
    List<Endpoint> findByEnabledTrueAndPausedAtNotNullAndPausedAtBefore(Instant threshold);
}
