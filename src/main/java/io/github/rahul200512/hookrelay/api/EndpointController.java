package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.domain.Endpoint;
import io.github.rahul200512.hookrelay.domain.EndpointRepository;
import io.github.rahul200512.hookrelay.domain.WebhookSigner;
import io.github.rahul200512.hookrelay.security.CurrentTenant;
import io.github.rahul200512.hookrelay.security.SsrfGuard;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EndpointController {

    static final int MAX_ENDPOINTS_PER_TENANT = 5;
    static final String EVENT_TYPE_PATTERN = "^[A-Za-z0-9_.:-]{1,100}$";

    public record CreateEndpointRequest(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 20) List<@NotBlank @Pattern(regexp = EVENT_TYPE_PATTERN) String> eventTypes,
            @Size(max = 500) String description) {}

    public record UpdateEndpointRequest(
            Boolean enabled,
            @Size(max = 500) String description,
            @Size(max = 20) List<@NotBlank @Pattern(regexp = EVENT_TYPE_PATTERN) String> eventTypes) {}

    public record EndpointResponse(UUID id, String url, String description, List<String> eventTypes, boolean enabled,
                                   Instant pausedAt, String pausedReason, int consecutiveFailures, Instant createdAt) {
        static EndpointResponse of(Endpoint e) {
            return new EndpointResponse(e.getId(), e.getUrl(), e.getDescription(),
                    e.getEventTypes() == null ? null : Arrays.asList(e.getEventTypes()), e.isEnabled(),
                    e.getPausedAt(), e.getPausedReason(), e.getConsecutiveFailures(), e.getCreatedAt());
        }
    }

    public record EndpointCreated(EndpointResponse endpoint, String secret, String note) {}

    private final EndpointRepository endpoints;
    private final SsrfGuard ssrfGuard;

    public EndpointController(EndpointRepository endpoints, SsrfGuard ssrfGuard) {
        this.endpoints = endpoints;
        this.ssrfGuard = ssrfGuard;
    }

    @Operation(summary = "Register a receiver URL", description = "Returns the signing secret once. eventTypes omitted or empty means all types.")
    @PostMapping("/v1/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public EndpointCreated create(@Valid @RequestBody CreateEndpointRequest body) {
        UUID tenantId = CurrentTenant.id();
        if (endpoints.countByTenantId(tenantId) >= MAX_ENDPOINTS_PER_TENANT) {
            throw new ApiErrors.LimitReached("A tenant may have at most " + MAX_ENDPOINTS_PER_TENANT + " endpoints.");
        }
        String url;
        try {
            url = ssrfGuard.validate(body.url()).toString();
        } catch (SsrfGuard.ForbiddenTargetException e) {
            throw new ApiErrors.InvalidTarget(e.getMessage());
        }
        String[] types = body.eventTypes() == null || body.eventTypes().isEmpty() ? null : body.eventTypes().toArray(String[]::new);
        Endpoint saved = endpoints.save(new Endpoint(tenantId, url, body.description(), WebhookSigner.newSecret(), types));
        return new EndpointCreated(EndpointResponse.of(saved), saved.getSecret(),
                "Verify deliveries with this secret (Standard Webhooks, HMAC-SHA256). It is not shown again.");
    }

    @GetMapping("/v1/endpoints")
    public List<EndpointResponse> list() {
        return endpoints.findByTenantIdOrderByCreatedAtDesc(CurrentTenant.id()).stream().map(EndpointResponse::of).toList();
    }

    @GetMapping("/v1/endpoints/{id}")
    public EndpointResponse get(@PathVariable UUID id) {
        return EndpointResponse.of(find(id));
    }

    @Operation(summary = "Update an endpoint", description = "Setting enabled=true also clears a pause.")
    @PatchMapping("/v1/endpoints/{id}")
    @Transactional
    public EndpointResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateEndpointRequest body) {
        Endpoint endpoint = find(id);
        if (body.enabled() != null) {
            if (body.enabled()) endpoint.enable(); else endpoint.disable("disabled by user", Instant.now());
        }
        if (body.description() != null) {
            endpoint.setDescription(body.description());
        }
        if (body.eventTypes() != null) {
            endpoint.setEventTypes(body.eventTypes().isEmpty() ? null : body.eventTypes().toArray(String[]::new));
        }
        return EndpointResponse.of(endpoints.save(endpoint));
    }

    @DeleteMapping("/v1/endpoints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        endpoints.delete(find(id));
    }

    private Endpoint find(UUID id) {
        return endpoints.findByIdAndTenantId(id, CurrentTenant.id()).orElseThrow(() -> new ApiErrors.NotFound("Endpoint"));
    }
}
