package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Its own context, because every other test creates a tenant in setup and would spend
 * this allowance immediately.
 *
 * <p>This exists because the limit did nothing in production while passing locally.
 * Keyed on the socket address it counted a proxy, not a caller, and behind a load
 * balancer whose address rotates every request looked like a new client. Sending the
 * forwarded header is what makes the test resemble the deployment rather than the laptop.
 */
@TestPropertySource(properties = "hookrelay.security.signups-per-hour-per-ip=5")
class SignupLimitIT extends IntegrationTest {

    private int signup(String forwardedFor, String name) {
        return http.post().uri("/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .body(Map.of("name", name))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void oneClientCannotCreateTenantsWithoutLimit() {
        var codes = IntStream.rangeClosed(1, 7)
                .map(i -> signup("203.0.113.42", "limited-" + i))
                .boxed().toList();

        assertThat(codes).startsWith(201, 201, 201, 201, 201);
        assertThat(codes.subList(5, 7)).containsOnly(429);
    }

    @Test
    void oneClientSpendingItsAllowanceDoesNotBlockAnother() {
        IntStream.rangeClosed(1, 6).forEach(i -> signup("198.51.100.1", "noisy-" + i));
        assertThat(signup("198.51.100.1", "noisy-again")).isEqualTo(429);
        assertThat(signup("198.51.100.2", "quiet-neighbour")).isEqualTo(201);
    }
}
