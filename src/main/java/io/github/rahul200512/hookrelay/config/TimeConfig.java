package io.github.rahul200512.hookrelay.config;

import io.github.rahul200512.hookrelay.crypto.SecretCrypto;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TimeConfig {

    private static final String NO_KEY = """
            No encryption key configured. Signing secrets are encrypted at rest, so the \
            service refuses to start without one rather than quietly storing them in the clear.

            Generate one and set it as APP_ENCRYPTION_KEY:
              openssl rand -base64 32
            """;

    /** Injected everywhere time is read, so tests can freeze or advance it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretCrypto secretCrypto(HookrelayProperties properties) {
        String key = properties.security().encryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(NO_KEY);
        }
        return new SecretCrypto(key);
    }
}
