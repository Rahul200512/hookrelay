package io.github.rahul200512.hookrelay.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed window per key, held in memory.
 *
 * <p>Honest about what it enforces: the count lives in this process, so with more than
 * one instance the effective limit is the limit times the instance count. It blunts a
 * script and protects the free-tier database; it is not a global quota. A shared counter
 * is the fix, and it needs somewhere shared to put it.
 */
public class FixedWindowLimiter {

    private record Window(Instant start, int count) {}

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final Duration window;
    private final Clock clock;

    public FixedWindowLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    public boolean allow(String key) {
        Instant now = clock.instant();
        if (windows.size() > MAX_TRACKED_KEYS) {
            // Unbounded growth is a slower outage than the one the limiter prevents.
            windows.entrySet().removeIf(e -> e.getValue().start().plus(window).isBefore(now));
            if (windows.size() > MAX_TRACKED_KEYS) {
                windows.clear();
            }
        }
        Window current = windows.compute(key, (k, previous) ->
                previous == null || previous.start().plus(window).isBefore(now)
                        ? new Window(now, 1)
                        : new Window(previous.start(), previous.count() + 1));
        return current.count() <= limit;
    }
}
