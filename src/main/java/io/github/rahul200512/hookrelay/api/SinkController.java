package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.domain.Sink;
import io.github.rahul200512.hookrelay.security.CurrentTenant;
import io.github.rahul200512.hookrelay.sink.SinkService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class SinkController {

    public record SinkResponse(UUID id, String url, Instant createdAt, String note) {}

    public record SinkRequestResponse(Instant receivedAt, JsonNode headers, String body) {}

    private final SinkService sinks;
    private final ObjectMapper mapper;

    public SinkController(SinkService sinks, ObjectMapper mapper) {
        this.sinks = sinks;
        this.mapper = mapper;
    }

    @Operation(summary = "Create a test receiver", description = "Register the returned URL as an endpoint; deliveries to it are recorded and readable below.")
    @PostMapping("/v1/sinks")
    @ResponseStatus(HttpStatus.CREATED)
    public SinkResponse create() {
        Sink sink = sinks.create(CurrentTenant.id());
        return new SinkResponse(sink.getId(), sinks.urlOf(sink), sink.getCreatedAt(),
                "POST this URL as an endpoint's url, then GET /v1/sinks/" + sink.getId() + "/requests.");
    }

    @Operation(summary = "The last 50 requests a sink received, newest first")
    @GetMapping("/v1/sinks/{id}/requests")
    public List<SinkRequestResponse> requests(@PathVariable UUID id) {
        Sink sink = sinks.find(CurrentTenant.id(), id).orElseThrow(() -> new ApiErrors.NotFound("Sink"));
        return sinks.recent(sink).stream()
                .map(r -> new SinkRequestResponse(r.getReceivedAt(), mapper.readTree(r.getHeaders()), r.getBody()))
                .toList();
    }
}
