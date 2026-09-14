package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RetryAndDeadLetterIT extends IntegrationTest {

    @Test
    void aFlakyReceiverIsRetriedUntilItAnswers() {
        receiver.script("flaky", 500, 503, 200);
        registerEndpoint(localUrl("/fake/flaky"));
        String deliveryId = publish("x.y", Map.of("k", "v")).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("SUCCEEDED"));

        JsonNode d = delivery(deliveryId);
        assertThat(d.get("attemptCount").asInt()).isEqualTo(3);
        JsonNode attempts = get("/v1/deliveries/" + deliveryId + "/attempts", apiKey).getBody();
        assertThat(attempts).extracting(a -> a.get("statusCode").asInt()).containsExactly(500, 503, 200);
        // Every retry carried the same webhook-id, so a receiver can dedupe.
        assertThat(receiver.received("flaky")).extracting(r -> r.headers().get("webhook-id")).containsOnly(deliveryId);
    }

    @Test
    void aReceiverThatNeverRecoversIsDeadLetteredAndTheEndpointPaused() {
        receiver.script("down", 503);
        JsonNode endpoint = registerEndpoint(localUrl("/fake/down")).get("endpoint");
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("DEAD"));
        assertThat(delivery(deliveryId).get("attemptCount").asInt()).isEqualTo(3); // two waits, three attempts

        JsonNode paused = get("/v1/endpoints/" + endpoint.get("id").asString(), apiKey).getBody();
        assertThat(paused.get("consecutiveFailures").asInt()).isEqualTo(3);
        assertThat(paused.get("pausedAt").isNull()).isFalse();

        // Paused endpoints get no new deliveries until re-enabled.
        assertThat(publish("x.y", Map.of()).get("deliveries")).isEmpty();
        patch("/v1/endpoints/" + endpoint.get("id").asString(), apiKey, Map.of("enabled", true));
        assertThat(publish("x.y", Map.of()).get("deliveries")).hasSize(1);
    }

    @Test
    void aClientErrorIsNotRetried() {
        receiver.script("reject", 400);
        registerEndpoint(localUrl("/fake/reject"));
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("DEAD"));
        assertThat(delivery(deliveryId).get("attemptCount").asInt()).isEqualTo(1);
        assertThat(delivery(deliveryId).get("lastStatusCode").asInt()).isEqualTo(400);
    }

    @Test
    void goneDisablesTheEndpoint() {
        receiver.script("gone", 410);
        JsonNode endpoint = registerEndpoint(localUrl("/fake/gone")).get("endpoint");
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("DEAD"));
        JsonNode disabled = get("/v1/endpoints/" + endpoint.get("id").asString(), apiKey).getBody();
        assertThat(disabled.get("enabled").asBoolean()).isFalse();
        assertThat(disabled.get("pausedReason").asString()).contains("410");
    }

    @Test
    void aTimeoutCountsAsAFailedAttemptAndIsRetried() {
        receiver.script("slow", 200);
        receiver.delay("slow", 3_000); // read-timeout is 2s in tests
        registerEndpoint(localUrl("/fake/slow"));
        String deliveryId = publish("x.y", Map.of()).get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode d = delivery(deliveryId);
            assertThat(d.get("attemptCount").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(d.get("lastError").isNull()).isFalse();
        });
        assertThat(delivery(deliveryId).get("lastStatusCode").isNull()).isTrue();
        receiver.delay("slow", 0);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("SUCCEEDED"));
    }
}
