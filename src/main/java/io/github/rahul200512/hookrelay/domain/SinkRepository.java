package io.github.rahul200512.hookrelay.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SinkRepository extends JpaRepository<Sink, UUID> {

    Optional<Sink> findByToken(String token);

    Optional<Sink> findByIdAndTenantId(UUID id, UUID tenantId);
}
