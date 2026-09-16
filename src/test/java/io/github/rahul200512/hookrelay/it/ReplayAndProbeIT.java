package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReplayAndProbeIT extends IntegrationTest {

    @Test
    void aDeadDeliveryCanBeReplayedAndTheAttemptLogKeepsBothRounds() {
        receiver.script("replay-me", 500);
        String endpointId = registerEndpoint(localUrl("/fake/replay-me")).get("endpoint").get("id").asString();
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("DEAD"));
        assertThat(delivery(deliveryId).get("attemptCount").asInt()).isEqualTo(3);

        // Three straight failures also paused the endpoint, so the operator brings it back
        // before asking for the event again. Replaying into a switched-off endpoint is
        // refused, which is the subject of its own test below.
        receiver.script("replay-me", 200);
        patch("/v1/endpoints/" + endpointId, apiKey, Map.of("enabled", true));
        var replayed = post("/v1/deliveries/" + deliveryId + "/replay", apiKey, null);
        assertThat(replayed.getStatusCode().value()).isEqualTo(200);
        assertThat(replayed.getBody().get("status").asString()).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("SUCCEEDED"));

        // A fresh budget, but the log is append-only: round one's three failures are still there.
        JsonNode attempts = get("/v1/deliveries/" + deliveryId + "/attempts", apiKey).getBody();
        assertThat(attempts).hasSize(4);
        assertThat(attempts).extracting(a -> a.get("statusCode").asInt()).containsExactly(500, 500, 500, 200);
        assertThat(attempts).extracting(a -> a.get("attemptNo").asInt()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void aDeliveryStillOnTheQueueCannotBeReplayed() {
        receiver.script("busy", 500);
        registerEndpoint(localUrl("/fake/busy"));
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        var response = post("/v1/deliveries/" + deliveryId + "/replay", apiKey, null);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("type").asString()).endsWith("#not-replayable");
    }

    @Test
    void replayIsRefusedWhileTheEndpointIsSwitchedOff() {
        receiver.script("off", 410);
        JsonNode endpoint = registerEndpoint(localUrl("/fake/off")).get("endpoint");
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("DEAD"));

        var refused = post("/v1/deliveries/" + deliveryId + "/replay", apiKey, null);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("detail").asString()).contains("410");

        // Turning the endpoint back on makes the replay legal again.
        receiver.script("off", 200);
        patch("/v1/endpoints/" + endpoint.get("id").asString(), apiKey, Map.of("enabled", true));
        assertThat(post("/v1/deliveries/" + deliveryId + "/replay", apiKey, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anotherTenantCannotReplayYourDelivery() {
        receiver.script("mine", 400);
        registerEndpoint(localUrl("/fake/mine"));
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("DEAD"));

        String other = createTenant("other").get("apiKey").asString();
        assertThat(post("/v1/deliveries/" + deliveryId + "/replay", other, null).getStatusCode().value()).isEqualTo(404);
    }
}
