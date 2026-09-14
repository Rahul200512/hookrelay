package io.github.rahul200512.hookrelay.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    List<Delivery> findByEventIdOrderByCreatedAtAsc(UUID eventId);

    Optional<Delivery> findByIdAndTenantId(UUID id, UUID tenantId);
}
