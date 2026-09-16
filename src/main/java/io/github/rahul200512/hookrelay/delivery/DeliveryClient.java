package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import io.github.rahul200512.hookrelay.domain.WebhookSigner;
import io.github.rahul200512.hookrelay.security.SsrfGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** One HTTP POST, signed, with hard timeouts, on a virtual thread. Never throws for HTTP outcomes. */
@Component
public class DeliveryClient {

    public record Outcome(Integer statusCode, String error, Optional<Duration> retryAfter, long durationMs) {
        public boolean isSuccess() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }

    private final RestClient client;
    private final SsrfGuard ssrfGuard;
    private final Clock clock;

    public DeliveryClient(HookrelayProperties properties, SsrfGuard ssrfGuard, Clock clock) {
        this.ssrfGuard = ssrfGuard;
        this.clock = clock;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.delivery().connectTimeout())
                // A redirect is a second URL we never validated.
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.delivery().readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public Outcome send(UUID deliveryId, String url, List<String> secrets, String body) {
        long started = System.nanoTime();
        URI target;
        try {
            target = ssrfGuard.validate(url);
        } catch (SsrfGuard.ForbiddenTargetException e) {
            return new Outcome(null, "target rejected: " + e.getMessage(), Optional.empty(), elapsed(started));
        }
        long timestamp = clock.instant().getEpochSecond();
        String signature = WebhookSigner.signAll(secrets, deliveryId.toString(), timestamp, body);
        try {
            return client.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("webhook-id", deliveryId.toString())
                    .header("webhook-timestamp", Long.toString(timestamp))
                    .header("webhook-signature", signature)
                    .header("user-agent", "hookrelay/0.1 (+https://github.com/Rahul200512/hookrelay)")
                    .body(body)
                    .exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        Optional<Duration> retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
                        return new Outcome(code, code >= 400 ? "HTTP " + code : null, retryAfter, elapsed(started));
                    }, false);
        } catch (RuntimeException e) {
            return new Outcome(null, describe(e), Optional.empty(), elapsed(started));
        }
    }

    /**
     * What goes in the attempt log, which is something a user reads to find out why their
     * webhook never arrived. Walks to the deepest cause, because the wrapper is always
     * ResourceAccessException and never says anything, and falls back to the class name:
     * ConnectException often carries no message at all, and "ConnectException: null"
     * tells nobody anything.
     */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String name = root.getClass().getSimpleName();
        return truncate(message == null || message.isBlank() ? name : name + ": " + message);
    }

    static Optional<Duration> parseRetryAfter(String header) {
        if (header == null) return Optional.empty();
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
        } catch (NumberFormatException notSeconds) {
            return Optional.empty(); // HTTP-date form is rare from webhook receivers; ignore it
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
