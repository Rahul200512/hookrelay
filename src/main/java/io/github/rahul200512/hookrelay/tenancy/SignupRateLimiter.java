package io.github.rahul200512.hookrelay.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fixed window per IP for the one unauthenticated write. In-memory, per instance, and
 * that is the honest description of what it enforces: it blunts a script, it is not a
 * global cap. Replaced by the real limiter in v2.
 */
@Component
public class SignupRateLimiter {

    private static final int LIMIT = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    private record Window(Instant start, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public SignupRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String ip) {
        Instant now = clock.instant();
        if (windows.size() > 10_000) {
            windows.clear();
        }
        Window w = windows.compute(ip, (k, prev) ->
                prev == null || prev.start().plus(WINDOW).isBefore(now) ? new Window(now, 1) : new Window(prev.start(), prev.count() + 1));
        return w.count() <= LIMIT;
    }
}
