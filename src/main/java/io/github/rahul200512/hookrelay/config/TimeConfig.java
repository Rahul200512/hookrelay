package io.github.rahul200512.hookrelay.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TimeConfig {

    /** Injected everywhere time is read, so tests can freeze or advance it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
