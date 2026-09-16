package io.github.rahul200512.hookrelay.it;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the real application against a real Postgres. One container and one context are
 * shared by every subclass. Skipped, not failed, on a machine without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hookrelay.security.allow-private-targets=true",
        "hookrelay.security.signups-per-hour-per-ip=1000",
        "hookrelay.public-url=http://localhost:0",
        "hookrelay.delivery.poll-interval=200ms",
        "hookrelay.delivery.backoff=200ms,200ms",
        "hookrelay.delivery.pause-after-consecutive-failures=3",
        "hookrelay.delivery.read-timeout=2s",
        "logging.level.io.github.rahul200512.hookrelay=DEBUG",
})
@Testcontainers(disabledWithoutDocker = true)
@Import({FakeReceiver.class, FakeReceiver.ReachableFromOutside.class})
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected FakeReceiver receiver;

    protected RestClient http;
    protected String apiKey;

    @BeforeEach
    void setUpClient() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        apiKey = createTenant("test-" + System.nanoTime()).get("apiKey").asString();
    }

    protected String localUrl(String path) {
        return "http://localhost:" + port + path;
    }

    protected JsonNode createTenant(String name) {
        return post("/v1/tenants", null, Map.of("name", name)).getBody();
    }

    protected ResponseEntity<JsonNode> post(String path, String key, Object body) {
        var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (key != null) spec = spec.header("Authorization", "Bearer " + key);
        if (body != null) spec = spec.body(body);
        return spec.retrieve().toEntity(JsonNode.class);
    }

    protected ResponseEntity<JsonNode> postWithHeader(String path, String key, Object body, String header, String value) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + key).header(header, value).body(body)
                .retrieve().toEntity(JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(String path, String key) {
        var spec = http.get().uri(path);
        if (key != null) spec = spec.header("Authorization", "Bearer " + key);
        return spec.retrieve().toEntity(JsonNode.class);
    }

    protected ResponseEntity<JsonNode> delete(String path, String key) {
        return http.delete().uri(path).header("Authorization", "Bearer " + key).retrieve().toEntity(JsonNode.class);
    }

    protected ResponseEntity<JsonNode> patch(String path, String key, Object body) {
        return http.patch().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + key).body(body).retrieve().toEntity(JsonNode.class);
    }

    /** Registers an endpoint at a URL on this same process and returns {endpoint, secret}. */
    protected JsonNode registerEndpoint(String url, String... eventTypes) {
        var body = eventTypes.length == 0 ? Map.of("url", url) : Map.of("url", url, "eventTypes", java.util.List.of(eventTypes));
        var response = post("/v1/endpoints", apiKey, body);
        if (response.getStatusCode().value() != 201) {
            throw new AssertionError("endpoint not created: " + response.getStatusCode() + " " + response.getBody());
        }
        return response.getBody();
    }

    protected JsonNode publish(String type, Map<String, Object> payload) {
        var response = post("/v1/events", apiKey, Map.of("type", type, "payload", payload));
        if (response.getStatusCode().value() != 201) {
            throw new AssertionError("event not created: " + response.getStatusCode() + " " + response.getBody());
        }
        return response.getBody();
    }

    protected JsonNode delivery(String id) {
        return get("/v1/deliveries/" + id, apiKey).getBody();
    }
}
