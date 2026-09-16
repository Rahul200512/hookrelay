package io.github.rahul200512.hookrelay.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EndpointRotationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    private Endpoint endpoint() {
        return new Endpoint(UUID.randomUUID(), "https://example.com/hook", null, "whsec_original", null);
    }

    @Test
    void beforeAnyRotationOnlyOneSecretSigns() {
        assertThat(endpoint().signingSecrets(NOW)).containsExactly("whsec_original");
    }

    @Test
    void insideTheOverlapBothSign_newestFirst() {
        Endpoint endpoint = endpoint();
        endpoint.rotateSecret("whsec_replacement", Duration.ofHours(24), NOW);

        assertThat(endpoint.getSecret()).isEqualTo("whsec_replacement");
        assertThat(endpoint.signingSecrets(NOW.plusSeconds(60)))
                .containsExactly("whsec_replacement", "whsec_original");
    }

    @Test
    void onceTheOverlapLapsesTheOldSecretStopsSigning() {
        Endpoint endpoint = endpoint();
        endpoint.rotateSecret("whsec_replacement", Duration.ofHours(24), NOW);

        assertThat(endpoint.signingSecrets(NOW.plus(Duration.ofHours(24)).plusSeconds(1)))
                .containsExactly("whsec_replacement");
    }

    @Test
    void rotatingTwiceInsideOneWindowDropsTheOldestRatherThanKeepingThree() {
        Endpoint endpoint = endpoint();
        endpoint.rotateSecret("whsec_second", Duration.ofHours(24), NOW);
        endpoint.rotateSecret("whsec_third", Duration.ofHours(24), NOW.plusSeconds(60));

        // Only ever two: a receiver updated to any live secret still verifies, and the
        // header does not grow without bound for someone who rotates in a loop.
        assertThat(endpoint.signingSecrets(NOW.plusSeconds(120)))
                .containsExactly("whsec_third", "whsec_second");
    }
}
