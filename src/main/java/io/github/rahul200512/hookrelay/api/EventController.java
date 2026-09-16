package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.domain.Delivery;
import io.github.rahul200512.hookrelay.domain.DeliveryRepository;
import io.github.rahul200512.hookrelay.domain.DeliveryStatus;
import io.github.rahul200512.hookrelay.domain.Event;
import io.github.rahul200512.hookrelay.domain.EventRepository;
import io.github.rahul200512.hookrelay.events.EventService;
import io.github.rahul200512.hookrelay.security.CurrentTenant;
import io.github.rahul200512.hookrelay.tenancy.RateLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class EventController {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    public record CreateEventRequest(
            @NotBlank @Pattern(regexp = EndpointController.EVENT_TYPE_PATTERN) String type,
            @NotNull JsonNode payload) {}

    public record DeliverySummary(UUID id, UUID endpointId, DeliveryStatus status) {}

    public record EventResponse(UUID id, String type, JsonNode payload, String idempotencyKey, Instant createdAt,
                                List<DeliverySummary> deliveries) {}

    private final EventService events;
    private final EventRepository eventRepository;
    private final DeliveryRepository deliveries;
    private final ObjectMapper mapper;
    private final RateLimits limits;

    public EventController(EventService events, EventRepository eventRepository, DeliveryRepository deliveries,
                           ObjectMapper mapper, RateLimits limits) {
        this.limits = limits;
        this.events = events;
        this.eventRepository = eventRepository;
        this.deliveries = deliveries;
        this.mapper = mapper;
    }

    @Operation(summary = "Publish an event",
            description = "Fans out one delivery per enabled endpoint that accepts the type. "
                    + "Send Idempotency-Key to make retries safe: a repeat returns the original event with 200 instead of 201.")
    @PostMapping("/v1/events")
    public ResponseEntity<EventResponse> create(
            @Valid @RequestBody CreateEventRequest body,
            @Parameter(description = "Any string up to 255 chars, unique per tenant.")
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 255) String idempotencyKey) {
        UUID tenantId = CurrentTenant.id();
        if (!limits.allowEvent(tenantId)) {
            throw new ApiErrors.TooManyRequests("This tenant is publishing too fast. Slow down and retry.");
        }
        String payload = mapper.writeValueAsString(body.payload());
        if (payload.length() > MAX_PAYLOAD_BYTES) {
            throw new ApiErrors.BadRequest("Payload exceeds " + MAX_PAYLOAD_BYTES + " bytes.");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        var result = events.create(tenantId, body.type(), payload, key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result.event(), result.deliveries()));
    }

    @GetMapping("/v1/events/{id}")
    public EventResponse get(@PathVariable UUID id) {
        Event event = eventRepository.findByIdAndTenantId(id, CurrentTenant.id()).orElseThrow(() -> new ApiErrors.NotFound("Event"));
        return toResponse(event, deliveries.findByEventIdOrderByCreatedAtAsc(event.getId()));
    }

    private EventResponse toResponse(Event event, List<Delivery> eventDeliveries) {
        return new EventResponse(event.getId(), event.getType(), mapper.readTree(event.getPayload()), event.getIdempotencyKey(),
                event.getCreatedAt(), eventDeliveries.stream()
                        .map(d -> new DeliverySummary(d.getId(), d.getEndpointId(), d.getStatus())).toList());
    }
}
