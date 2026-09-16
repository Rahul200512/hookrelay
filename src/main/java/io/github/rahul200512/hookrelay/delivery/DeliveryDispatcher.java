package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import io.github.rahul200512.hookrelay.domain.Endpoint;
import io.github.rahul200512.hookrelay.domain.EndpointRepository;
import io.github.rahul200512.hookrelay.domain.Event;
import io.github.rahul200512.hookrelay.domain.EventRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs each claimed delivery on its own thread, bounded two ways: a global in-flight cap
 * so the process can't exhaust sockets, and a per-endpoint cap so one slow receiver can't
 * occupy the global budget while everyone else waits.
 *
 * <p>Three phases, and no database connection is held across the middle one: read what
 * the call needs, make the call, persist the result.
 *
 * <p>The thread model is configuration rather than a hard-coded choice, so "virtual
 * threads are the right call for this workload" is something the benchmark can show
 * instead of something the README asserts.
 */
@Component
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final DeliveryClient client;
    private final DeliveryOutcomes outcomes;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ExecutorService executor;
    private final Semaphore inFlight;
    private final int perEndpointLimit;
    private final Map<UUID, Semaphore> perEndpoint = new ConcurrentHashMap<>();

    public DeliveryDispatcher(EventRepository events, EndpointRepository endpoints, DeliveryClient client,
                              DeliveryOutcomes outcomes, ObjectMapper mapper, Clock clock, HookrelayProperties properties) {
        this.events = events;
        this.endpoints = endpoints;
        this.client = client;
        this.outcomes = outcomes;
        this.mapper = mapper;
        this.clock = clock;
        this.inFlight = new Semaphore(properties.delivery().maxInFlight());
        this.perEndpointLimit = properties.delivery().perEndpointInFlight();
        var threads = properties.delivery().threads();
        this.executor = switch (threads.model()) {
            case VIRTUAL -> Executors.newVirtualThreadPerTaskExecutor();
            case PLATFORM -> Executors.newFixedThreadPool(threads.platformPoolSize());
        };
        log.info("delivery executor: {}{}", threads.model(),
                threads.model() == HookrelayProperties.Threads.Model.PLATFORM
                        ? " (" + threads.platformPoolSize() + " threads)" : "");
    }

    /** How many more deliveries can be started right now; the poller claims no more than this. */
    public int capacity() {
        return inFlight.availablePermits();
    }

    public void dispatch(DeliveryQueue.Claimed claimed) {
        inFlight.acquireUninterruptibly();
        executor.submit(() -> {
            try {
                deliver(claimed);
            } catch (RuntimeException e) {
                // The lease will lapse and the row will be re-claimed; log, don't lose the thread pool.
                log.error("delivery {} failed unexpectedly", claimed.id(), e);
            } finally {
                inFlight.release();
            }
        });
    }

    private void deliver(DeliveryQueue.Claimed claimed) {
        // Phase 1: read. Two indexed lookups, transaction-free, connection returned at once.
        Event event = events.findById(claimed.eventId()).orElseThrow();
        Endpoint endpoint = endpoints.findById(claimed.endpointId()).orElseThrow();
        String body = envelope(event);

        // Phase 2: call. Nothing held but the semaphores.
        Semaphore gate = perEndpoint.computeIfAbsent(endpoint.getId(), id -> new Semaphore(perEndpointLimit));
        Instant startedAt = clock.instant();
        DeliveryClient.Outcome outcome;
        gate.acquireUninterruptibly();
        try {
            outcome = client.send(claimed.id(), endpoint.getUrl(), endpoint.signingSecrets(startedAt), body);
        } finally {
            gate.release();
        }

        // Phase 3: persist, in one short transaction.
        outcomes.record(claimed.id(), claimed.attemptNo(), startedAt, outcome);
    }

    /** The body receivers get. Built once per attempt and signed byte-for-byte as sent. */
    String envelope(Event event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", event.getId().toString());
        envelope.put("type", event.getType());
        envelope.put("timestamp", event.getCreatedAt().toString());
        envelope.put("data", mapper.readTree(event.getPayload()));
        return mapper.writeValueAsString(envelope);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        // In-flight sends finish or time out; anything still RUNNING is re-claimed when its lease lapses.
        executor.awaitTermination(15, TimeUnit.SECONDS);
    }
}
