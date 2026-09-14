package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.domain.BackoffPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DeliveryConfig {

    /**
     * Seven waits, eight attempts, about twenty hours end to end. Overridable so tests
     * can run the whole schedule in milliseconds.
     */
    @Bean
    BackoffPolicy backoffPolicy(@Value("${hookrelay.delivery.backoff:10s,1m,5m,30m,2h,6h,12h}") List<Duration> schedule) {
        return new BackoffPolicy(schedule);
    }
}
