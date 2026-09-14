package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.domain.Delivery;
import io.github.rahul200512.hookrelay.domain.DeliveryAttemptRepository;
import io.github.rahul200512.hookrelay.domain.DeliveryRepository;
import io.github.rahul200512.hookrelay.domain.DeliveryStatus;
import io.github.rahul200512.hookrelay.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
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

    public DeliveryController(DeliveryRepository deliveries, DeliveryAttemptRepository attempts) {
        this.deliveries = deliveries;
        this.attempts = attempts;
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

    private Delivery find(UUID id) {
        return deliveries.findByIdAndTenantId(id, CurrentTenant.id()).orElseThrow(() -> new ApiErrors.NotFound("Delivery"));
    }
}
