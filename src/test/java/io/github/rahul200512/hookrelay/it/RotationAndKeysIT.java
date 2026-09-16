package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.rahul200512.hookrelay.domain.WebhookSigner;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class RotationAndKeysIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void aRotatedSecretKeepsSigningSoAReceiverCanBeUpdatedWithoutDroppingADelivery() {
        JsonNode sink = post("/v1/sinks", apiKey, null).getBody();
        String sinkUrl = sink.get("url").asString().replace("localhost:0", "localhost:" + port);
        JsonNode created = registerEndpoint(sinkUrl);
        String endpointId = created.get("endpoint").get("id").asString();
        String oldSecret = created.get("secret").asString();

        var rotated = post("/v1/endpoints/" + endpointId + "/rotate-secret", apiKey, null);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String newSecret = rotated.getBody().get("secret").asString();
        assertThat(newSecret).startsWith("whsec_").isNotEqualTo(oldSecret);

        String deliveryId = publish("x.y", Map.of("n", 1)).get("deliveries").get(0).get("id").asString();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(delivery(deliveryId).get("status").asString()).isEqualTo("SUCCEEDED"));

        JsonNode request = get("/v1/sinks/" + sink.get("id").asString() + "/requests", apiKey).getBody().get(0);
        String header = request.get("headers").get("webhook-signature").asString();
        long ts = Long.parseLong(request.get("headers").get("webhook-timestamp").asString());
        String body = request.get("body").asString();

        assertThat(header.split(" ")).as("both secrets signed it").hasSize(2);
        assertThat(WebhookSigner.verify(newSecret, deliveryId, ts, body, header))
                .as("a receiver already updated verifies").isTrue();
        assertThat(WebhookSigner.verify(oldSecret, deliveryId, ts, body, header))
                .as("a receiver not yet updated also verifies").isTrue();
    }

    @Test
    void theSigningSecretIsNotReadableInTheDatabase() {
        JsonNode created = registerEndpoint(localUrl("/fake/enc"));
        String secret = created.get("secret").asString();
        String endpointId = created.get("endpoint").get("id").asString();

        String stored = jdbc.sql("select secret from endpoints where id = cast(:id as uuid)")
                .param("id", endpointId).query(String.class).single();

        assertThat(stored).startsWith("v1:").doesNotContain(secret);
        assertThat(stored).doesNotContain("whsec_");
    }

    @Test
    void aSecondKeyLetsATenantRotateWithoutLockingItselfOut() {
        var issued = post("/v1/api-keys", apiKey, null);
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        String secondKey = issued.getBody().get("apiKey").asString();
        String secondKeyId = issued.getBody().get("key").get("id").asString();

        // Both work.
        assertThat(get("/v1/endpoints", apiKey).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/v1/endpoints", secondKey).getStatusCode().value()).isEqualTo(200);

        // Only the prefix is ever readable back.
        JsonNode listed = get("/v1/api-keys", apiKey).getBody();
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("prefix").asString()).startsWith("hr_live_");
        assertThat(listed.toString()).doesNotContain(secondKey);

        // Revoke the new one; it stops working immediately, the original still does.
        assertThat(delete("/v1/api-keys/" + secondKeyId, apiKey).getStatusCode().value()).isEqualTo(204);
        assertThat(get("/v1/endpoints", secondKey).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/v1/endpoints", apiKey).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void thelastActiveKeyCannotBeRevoked() {
        String keyId = get("/v1/api-keys", apiKey).getBody().get(0).get("id").asString();
        var refused = delete("/v1/api-keys/" + keyId, apiKey);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("detail").asString()).contains("only active key");
        assertThat(get("/v1/endpoints", apiKey).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anotherTenantsKeyIsNotVisibleOrRevocable() {
        String mine = get("/v1/api-keys", apiKey).getBody().get(0).get("id").asString();
        String other = createTenant("other").get("apiKey").asString();
        assertThat(get("/v1/api-keys", other).getBody()).hasSize(1);
        assertThat(delete("/v1/api-keys/" + mine, other).getStatusCode().value()).isEqualTo(404);
    }
}
