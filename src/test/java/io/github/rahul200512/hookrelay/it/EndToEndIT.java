package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.rahul200512.hookrelay.domain.WebhookSigner;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class EndToEndIT extends IntegrationTest {

    @Test
    void theReadmeLoop_signup_sink_endpoint_event_deliveredAndSigned() {
        // A sink is a receiver we host; its public URL is rewritten to this test's port.
        JsonNode sink = post("/v1/sinks", apiKey, null).getBody();
        String sinkUrl = sink.get("url").asString().replace("localhost:0", "localhost:" + port);

        JsonNode created = registerEndpoint(sinkUrl, "order.paid");
        String secret = created.get("secret").asString();
        assertThat(secret).startsWith("whsec_");

        JsonNode event = publish("order.paid", Map.of("orderId", 42, "amount", "19.99"));
        assertThat(event.get("deliveries")).hasSize(1);
        String deliveryId = event.get("deliveries").get(0).get("id").asString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("SUCCEEDED"));

        JsonNode requests = get("/v1/sinks/" + sink.get("id").asString() + "/requests", apiKey).getBody();
        assertThat(requests).hasSize(1);
        JsonNode headers = requests.get(0).get("headers");
        String body = requests.get(0).get("body").asString();

        // Standard Webhooks: id is the delivery id, and the signature verifies over id.timestamp.body.
        assertThat(headers.get("webhook-id").asString()).isEqualTo(deliveryId);
        long ts = Long.parseLong(headers.get("webhook-timestamp").asString());
        assertThat(WebhookSigner.verify(secret, deliveryId, ts, body, headers.get("webhook-signature").asString())).isTrue();
        assertThat(headers.get("content-type").asString()).startsWith("application/json");

        JsonNode envelope = json.readTree(body);
        assertThat(envelope.get("id").asString()).isEqualTo(event.get("id").asString());
        assertThat(envelope.get("type").asString()).isEqualTo("order.paid");
        assertThat(envelope.get("data").get("orderId").asInt()).isEqualTo(42);

        JsonNode attempts = get("/v1/deliveries/" + deliveryId + "/attempts", apiKey).getBody();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("statusCode").asInt()).isEqualTo(200);
    }

    @Test
    void idempotencyKeyReturnsTheOriginalEventWith200() {
        registerEndpoint(localUrl("/fake/idem"));
        var first = postWithHeader("/v1/events", apiKey, Map.of("type", "a.b", "payload", Map.of("n", 1)), "Idempotency-Key", "k-1");
        var second = postWithHeader("/v1/events", apiKey, Map.of("type", "a.b", "payload", Map.of("n", 1)), "Idempotency-Key", "k-1");
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getBody().get("deliveries").get(0).get("id")).isEqualTo(first.getBody().get("deliveries").get(0).get("id"));

        // A different tenant may reuse the key.
        String other = createTenant("other").get("apiKey").asString();
        var third = postWithHeader("/v1/events", other, Map.of("type", "a.b", "payload", Map.of("n", 1)), "Idempotency-Key", "k-1");
        assertThat(third.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void endpointsOnlyReceiveTheTypesTheySubscribedTo() {
        registerEndpoint(localUrl("/fake/only-paid"), "order.paid");
        registerEndpoint(localUrl("/fake/everything"));
        JsonNode event = publish("order.refunded", Map.of());
        assertThat(event.get("deliveries")).hasSize(1);
    }
}
