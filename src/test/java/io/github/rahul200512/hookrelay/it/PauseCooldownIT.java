package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Its own context, with the cooldown turned down from fifteen minutes to one second.
 * Every other test wants a pause to stay put while it makes assertions about it.
 */
@TestPropertySource(properties = {
        "hookrelay.delivery.pause-cooldown=1s",
        "hookrelay.delivery.probe-interval=300ms",
})
class PauseCooldownIT extends IntegrationTest {

    @Test
    void aPausedEndpointIsLetBackInAfterTheCooldown() {
        receiver.script("recovers", 503);
        JsonNode endpoint = registerEndpoint(localUrl("/fake/recovers")).get("endpoint");
        String endpointId = endpoint.get("id").asString();
        publish("x.y", Map.of());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(get("/v1/endpoints/" + endpointId, apiKey).getBody().get("pausedAt").isNull()).isFalse());

        // The receiver recovers while the endpoint is in its cooldown.
        receiver.script("recovers", 200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode e = get("/v1/endpoints/" + endpointId, apiKey).getBody();
            assertThat(e.get("pausedAt").isNull()).as("the pause was lifted").isTrue();
            assertThat(e.get("consecutiveFailures").asInt()).isZero();
        });

        // And it takes real traffic again.
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("SUCCEEDED"));
    }

    @Test
    void anEndpointTheUserSwitchedOffIsNeverLetBackInByItself() {
        receiver.script("gone-for-good", 410);
        JsonNode endpoint = registerEndpoint(localUrl("/fake/gone-for-good")).get("endpoint");
        String endpointId = endpoint.get("id").asString();
        publish("x.y", Map.of());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(get("/v1/endpoints/" + endpointId, apiKey).getBody().get("enabled").asBoolean()).isFalse());

        // Well past the cooldown, a 410 stays off: only the user can undo it.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(get("/v1/endpoints/" + endpointId, apiKey).getBody().get("enabled").asBoolean()).isFalse());
    }
}
