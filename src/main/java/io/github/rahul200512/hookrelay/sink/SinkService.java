package io.github.rahul200512.hookrelay.sink;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import io.github.rahul200512.hookrelay.domain.Sink;
import io.github.rahul200512.hookrelay.domain.SinkRepository;
import io.github.rahul200512.hookrelay.domain.SinkRequest;
import io.github.rahul200512.hookrelay.domain.SinkRequestRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * A receiver you don't have to host. Register the sink's URL as an endpoint and every
 * delivery, headers and all, shows up under GET /v1/sinks/{id}/requests. It is what
 * lets the README demo run end to end with nothing but curl.
 */
@Service
public class SinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SinkRepository sinks;
    private final SinkRequestRepository requests;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String publicUrl;

    public SinkService(SinkRepository sinks, SinkRequestRepository requests, ObjectMapper mapper,
                       Clock clock, HookrelayProperties properties) {
        this.sinks = sinks;
        this.requests = requests;
        this.mapper = mapper;
        this.clock = clock;
        this.publicUrl = properties.publicUrl().replaceAll("/+$", "");
    }

    public String urlOf(Sink sink) {
        return publicUrl + "/sink/" + sink.getToken();
    }

    @Transactional
    public Sink create(UUID tenantId) {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return sinks.save(new Sink(tenantId, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    public Optional<Sink> find(UUID tenantId, UUID id) {
        return sinks.findByIdAndTenantId(id, tenantId);
    }

    public List<SinkRequest> recent(Sink sink) {
        return requests.findTop50BySinkIdOrderByReceivedAtDesc(sink.getId());
    }

    /** @return false if no sink has this token */
    @Transactional
    public boolean receive(String token, Map<String, String> headers, String body) {
        var sink = sinks.findByToken(token);
        if (sink.isEmpty()) {
            return false;
        }
        requests.save(new SinkRequest(sink.get().getId(), mapper.writeValueAsString(headers), body, clock.instant()));
        return true;
    }
}
