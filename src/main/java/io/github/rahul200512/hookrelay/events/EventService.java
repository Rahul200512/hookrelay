package io.github.rahul200512.hookrelay.events;

import io.github.rahul200512.hookrelay.domain.Delivery;
import io.github.rahul200512.hookrelay.domain.Endpoint;
import io.github.rahul200512.hookrelay.domain.Event;
import io.github.rahul200512.hookrelay.domain.Repositories;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventService {

    public record Result(Event event, List<Delivery> deliveries, boolean replayed) {}

    private final Repositories.Events events;
    private final Repositories.Endpoints endpoints;
    private final Repositories.Deliveries deliveries;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EventService(Repositories.Events events, Repositories.Endpoints endpoints,
                        Repositories.Deliveries deliveries, TransactionTemplate tx, Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Creates the event and one PENDING delivery per matching endpoint, atomically.
     *
     * <p>Idempotency: the unique index on (tenant_id, idempotency_key) is the source of
     * truth, not a read-then-write. Two concurrent requests with the same key both try
     * to insert; one wins, the other's transaction fails with a constraint violation and
     * is answered with the winner's row. No lock, no window.
     */
    public Result create(UUID tenantId, String type, String payloadJson, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = events.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get());
            }
        }
        try {
            return tx.execute(status -> insert(tenantId, type, payloadJson, idempotencyKey));
        } catch (DataIntegrityViolationException raced) {
            if (idempotencyKey == null) {
                throw raced;
            }
            return events.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                    .map(this::replay)
                    .orElseThrow(() -> raced);
        }
    }

    private Result insert(UUID tenantId, String type, String payloadJson, String idempotencyKey) {
        Event event = events.save(new Event(tenantId, type, payloadJson, idempotencyKey, clock.instant()));
        List<Endpoint> targets = endpoints.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(Endpoint::isDeliverable)
                .filter(e -> e.accepts(type))
                .toList();
        List<Delivery> created = deliveries.saveAll(targets.stream()
                .map(endpoint -> new Delivery(event, endpoint, clock.instant()))
                .toList());
        return new Result(event, created, false);
    }

    private Result replay(Event event) {
        return new Result(event, deliveries.findByEventIdOrderByCreatedAtAsc(event.getId()), true);
    }
}
