package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import io.github.rahul200512.hookrelay.domain.Endpoint;
import io.github.rahul200512.hookrelay.domain.EndpointRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifts an automatic pause once the receiver has had time to recover.
 *
 * <p>There is no synthetic ping. Sending a made-up request to someone's production URL
 * to see whether they answer is rude, and it proves less than it looks: a receiver can
 * answer a probe and still reject real events. The probe here is the next real event.
 * If the receiver is still broken the endpoint simply pauses again, so a dead URL costs
 * one failed delivery per cooldown rather than a permanent retry storm.
 *
 * <p>Only self-inflicted pauses are lifted. An endpoint the user disabled, or one that
 * answered 410 Gone, stays off until the user turns it back on.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.delivery.poller-enabled", havingValue = "true", matchIfMissing = true)
public class EndpointProbe {

    private static final Logger log = LoggerFactory.getLogger(EndpointProbe.class);

    private final EndpointRepository endpoints;
    private final Duration cooldown;
    private final Clock clock;

    public EndpointProbe(EndpointRepository endpoints, HookrelayProperties properties, Clock clock) {
        this.endpoints = endpoints;
        this.cooldown = properties.delivery().pauseCooldown();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hookrelay.delivery.probe-interval:60s}",
            initialDelayString = "${hookrelay.delivery.probe-interval:60s}")
    @Transactional
    public void liftExpiredPauses() {
        List<Endpoint> due = endpoints.findByEnabledTrueAndPausedAtNotNullAndPausedAtBefore(clock.instant().minus(cooldown));
        for (Endpoint endpoint : due) {
            endpoint.enable();
            log.info("endpoint {} back in rotation after a {} cooldown", endpoint.getId(), cooldown);
        }
        if (!due.isEmpty()) {
            endpoints.saveAll(due);
        }
    }
}
