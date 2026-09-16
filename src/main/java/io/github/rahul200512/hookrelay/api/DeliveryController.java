package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.domain.Delivery;
import io.github.rahul200512.hookrelay.domain.DeliveryAttemptRepository;
import io.github.rahul200512.hookrelay.domain.DeliveryRepository;
import io.github.rahul200512.hookrelay.domain.EndpointRepository;
import io.github.rahul200512.hookrelay.domain.DeliveryStatus;
import io.github.rahul200512.hookrelay.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeliveryController {

    public record DeliveryResponse(UUID id, UUID eventId, UUID endpointId, DeliveryStatus status, int attemptCount,
                                   Instant nextAttemptAt, Integer lastStatusCode, String lastError,
                                   Instant createdAt, Instant updatedAt) {
        static DeliveryResponse of(Delivery d) {
            return new DeliveryResponse(d.getId(), d.getEventId(), d.getEndpointId(), d.getStatus(), d.getAttemptCount(),
                    d.getStatus() == DeliveryStatus.PENDING ? d.getNextAttemptAt() : null,
                    d.getLastStatusCode(), d.getLastError(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record AttemptResponse(int attemptNo, Instant startedAt, int durationMs, Integer statusCode, String error) {}

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final EndpointRepository endpoints;
    private final Clock clock;

    public DeliveryController(DeliveryRepository deliveries, DeliveryAttemptRepository attempts,
                              EndpointRepository endpoints, Clock clock) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    @Operation(summary = "Delivery status")
    @GetMapping("/v1/deliveries/{id}")
    public DeliveryResponse get(@PathVariable UUID id) {
        return DeliveryResponse.of(find(id));
    }

    @Operation(summary = "Every attempt made for a delivery, oldest first")
    @GetMapping("/v1/deliveries/{id}/attempts")
    public List<AttemptResponse> attempts(@PathVariable UUID id) {
        Delivery delivery = find(id);
        return attempts.findByDeliveryIdOrderByAttemptNoAsc(delivery.getId()).stream()
                .map(a -> new AttemptResponse(a.getAttemptNo(), a.getStartedAt(), a.getDurationMs(), a.getStatusCode(), a.getError()))
                .toList();
    }

    @Operation(summary = "Send a finished delivery again",
            description = "Puts a dead or succeeded delivery back on the queue with a fresh retry budget. "
                    + "The attempt log is not reset: replayed attempts are appended, so the whole history stays readable. "
                    + "A delivery still pending or running is refused, and so is one whose endpoint is switched off.")
    @PostMapping("/v1/deliveries/{id}/replay")
    @Transactional
    public DeliveryResponse replay(@PathVariable UUID id) {
        Delivery delivery = find(id);
        if (!delivery.isReplayable()) {
            throw new ApiErrors.NotReplayable("This delivery is " + delivery.getStatus()
                    + "; only a delivery that has finished (DEAD or SUCCEEDED) can be replayed.");
        }
        var endpoint = endpoints.findById(delivery.getEndpointId()).orElseThrow(() -> new ApiErrors.NotFound("Endpoint"));
        if (!endpoint.isDeliverable()) {
            throw new ApiErrors.NotReplayable("The endpoint is not accepting deliveries"
                    + (endpoint.getPausedReason() == null ? "." : ": " + endpoint.getPausedReason() + "."));
        }
        delivery.replay(clock.instant());
        return DeliveryResponse.of(deliveries.save(delivery));
    }

    private Delivery find(UUID id) {
        return deliveries.findByIdAndTenantId(id, CurrentTenant.id()).orElseThrow(() -> new ApiErrors.NotFound("Delivery"));
    }
}
