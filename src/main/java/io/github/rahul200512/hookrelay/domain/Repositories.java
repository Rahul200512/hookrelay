package io.github.rahul200512.hookrelay.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One file for the repositories: they are declarations, not code. */
public final class Repositories {
    private Repositories() {}

    public interface Tenants extends JpaRepository<Tenant, UUID> {}

    public interface ApiKeys extends JpaRepository<ApiKey, UUID> {
        Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
    }

    public interface Endpoints extends JpaRepository<Endpoint, UUID> {
        List<Endpoint> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
        Optional<Endpoint> findByIdAndTenantId(UUID id, UUID tenantId);
        long countByTenantId(UUID tenantId);
    }

    public interface Events extends JpaRepository<Event, UUID> {
        Optional<Event> findByIdAndTenantId(UUID id, UUID tenantId);
        Optional<Event> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
    }

    public interface Deliveries extends JpaRepository<Delivery, UUID> {
        List<Delivery> findByEventIdOrderByCreatedAtAsc(UUID eventId);
        Optional<Delivery> findByIdAndTenantId(UUID id, UUID tenantId);
    }

    public interface DeliveryAttempts extends JpaRepository<DeliveryAttempt, UUID> {
        List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNoAsc(UUID deliveryId);
    }

    public interface Sinks extends JpaRepository<Sink, UUID> {
        Optional<Sink> findByToken(String token);
        Optional<Sink> findByIdAndTenantId(UUID id, UUID tenantId);
    }

    public interface SinkRequests extends JpaRepository<SinkRequest, UUID> {
        List<SinkRequest> findTop50BySinkIdOrderByReceivedAtDesc(UUID sinkId);
    }
}
