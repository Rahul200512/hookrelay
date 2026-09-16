package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import io.github.rahul200512.hookrelay.domain.BackoffPolicy;
import io.github.rahul200512.hookrelay.domain.Delivery;
import io.github.rahul200512.hookrelay.domain.DeliveryAttempt;
import io.github.rahul200512.hookrelay.domain.DeliveryAttemptRepository;
import io.github.rahul200512.hookrelay.domain.DeliveryRepository;
import io.github.rahul200512.hookrelay.domain.Endpoint;
import io.github.rahul200512.hookrelay.domain.EndpointRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an HTTP outcome into state: one attempt row, the delivery's next status, and the
 * endpoint's health counter. Runs in its own short transaction, after the HTTP call has
 * completed and with nothing else held.
 *
 * <p>Classification: 2xx succeeds. 410 Gone means the receiver is telling us to stop, so
 * the endpoint is disabled. Other 4xx (except 408/425/429) will not get better by
 * retrying and dead-letter immediately. Everything else (5xx, 429, timeouts, connection
 * errors) retries on the backoff schedule until attempts run out.
 */
@Component
public class DeliveryOutcomes {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomes.class);
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final EndpointRepository endpoints;
    private final BackoffPolicy backoff;
    private final int pauseAfter;
    private final Clock clock;
    private final MeterRegistry metrics;

    public DeliveryOutcomes(DeliveryRepository deliveries, DeliveryAttemptRepository attempts,
                            EndpointRepository endpoints, BackoffPolicy backoff,
                            HookrelayProperties properties, Clock clock, MeterRegistry metrics) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.endpoints = endpoints;
        this.backoff = backoff;
        this.pauseAfter = properties.delivery().pauseAfterConsecutiveFailures();
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public void record(UUID deliveryId, int attemptNo, Instant startedAt, DeliveryClient.Outcome outcome) {
        Delivery delivery = deliveries.findById(deliveryId).orElseThrow();
        Endpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElseThrow();
        Instant now = clock.instant();

        attempts.save(new DeliveryAttempt(deliveryId, attemptNo, startedAt, (int) Math.min(outcome.durationMs(), Integer.MAX_VALUE),
                outcome.statusCode(), outcome.error()));
        metrics.timer("hookrelay.delivery.latency").record(outcome.durationMs(), TimeUnit.MILLISECONDS);

        String result;
        if (outcome.isSuccess()) {
            delivery.succeeded(outcome.statusCode());
            endpoint.recordSuccess();
            result = "succeeded";
        } else if (outcome.statusCode() != null && outcome.statusCode() == 410) {
            delivery.dead(410, "endpoint answered 410 Gone");
            endpoint.disable("receiver answered 410 Gone", now);
            result = "dead";
        } else if (isPermanentClientError(outcome.statusCode())) {
            delivery.dead(outcome.statusCode(), outcome.error());
            endpoint.recordFailure(pauseAfter, now);
            result = "dead";
        } else {
            boolean paused = endpoint.recordFailure(pauseAfter, now);
            int attemptInRound = delivery.attemptInRound(attemptNo);
            var delay = outcome.retryAfter()
                    .map(d -> d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d)
                    .or(() -> backoff.delayAfter(attemptInRound));
            if (delay.isPresent() && attemptInRound < backoff.maxAttempts()) {
                delivery.retryAt(now.plus(delay.get()), outcome.statusCode(), outcome.error());
                result = "retry";
            } else {
                delivery.dead(outcome.statusCode(), outcome.error());
                result = "dead";
            }
            if (paused) {
                log.warn("endpoint {} paused after {} consecutive failures", endpoint.getId(), endpoint.getConsecutiveFailures());
            }
        }
        metrics.counter("hookrelay.deliveries", "outcome", result).increment();
        log.info("delivery {} attempt {} -> {} (status={}, {} ms)", deliveryId, attemptNo, result, outcome.statusCode(), outcome.durationMs());
    }

    static boolean isPermanentClientError(Integer code) {
        return code != null && code >= 400 && code < 500 && code != 408 && code != 425 && code != 429;
    }
}
