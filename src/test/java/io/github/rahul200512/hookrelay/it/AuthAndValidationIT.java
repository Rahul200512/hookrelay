package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthAndValidationIT extends IntegrationTest {

    @Test
    void protectedRoutesAnswer401AsProblemDetails() {
        var none = get("/v1/endpoints", null);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
        assertThat(none.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(none.getBody().get("type").asString()).endsWith("#unauthenticated");

        var bad = get("/v1/endpoints", "hr_live_definitely-not-a-key");
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        assertThat(bad.getBody().get("type").asString()).endsWith("#invalid-api-key");
    }

    @Test
    void tenantsCannotSeeEachOthersResources() {
        String id = registerEndpoint(localUrl("/fake/mine")).get("endpoint").get("id").asString();
        String other = createTenant("other").get("apiKey").asString();
        var response = get("/v1/endpoints/" + id, other);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().get("type").asString()).endsWith("#not-found");
    }

    @Test
    void validationFailuresAreProblemDetails() {
        var response = post("/v1/events", apiKey, Map.of("type", "has spaces", "payload", Map.of()));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void privateTargetsAreRefusedEvenInDevModeForNonHttpSchemes() {
        var response = post("/v1/endpoints", apiKey, Map.of("url", "ftp://example.com/x"));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("type").asString()).endsWith("#invalid-target");
    }

    @Test
    void theRootSendsAVisitorToTheDocsRatherThanA401() {
        // Opening the service in a browser used to answer a bare 401 problem document.
        // Correct, and the worst possible first thing to show someone handed the link.
        var response = http.get().uri("/").retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasToString("/swagger-ui.html");
    }

    @Test
    void publicRoutesNeedNoKey() {
        assertThat(get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/v3/api-docs", null).getStatusCode().value()).isEqualTo(200);
    }
}
