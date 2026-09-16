package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Claims due deliveries on a fixed delay and hands them to the dispatcher.
 *
 * <p>Switchable so a node can serve the API without also working the queue, and so the
 * queue's own tests can drive claiming directly instead of racing a background poller.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.delivery.poller-enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryPoller {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPoller.class);

    private final DeliveryQueue queue;
    private final DeliveryDispatcher dispatcher;
    private final int batchSize;

    public DeliveryPoller(DeliveryQueue queue, DeliveryDispatcher dispatcher, HookrelayProperties properties) {
        this.queue = queue;
        this.dispatcher = dispatcher;
        this.batchSize = properties.delivery().batchSize();
    }

    @Scheduled(fixedDelayString = "${hookrelay.delivery.poll-interval}", initialDelayString = "${hookrelay.delivery.poll-interval}")
    public void poll() {
        int wanted = Math.min(batchSize, dispatcher.capacity());
        if (wanted == 0) {
            return;
        }
        var claimed = queue.claim(wanted);
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("claimed {} deliveries", claimed.size());
        claimed.forEach(dispatcher::dispatch);
    }
}
