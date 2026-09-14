package io.github.rahul200512.hookrelay.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry schedule with equal jitter: the wait after attempt {@code n} is
 * {@code base/2 + random(0, base/2)}. Full jitter can produce a zero wait, which
 * retries a receiver that answered "not now" immediately; half the base is the
 * floor.
 */
public final class BackoffPolicy {

    private final List<Duration> schedule;

    public BackoffPolicy(List<Duration> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            throw new IllegalArgumentException("backoff schedule must not be empty");
        }
        this.schedule = List.copyOf(schedule);
    }

    /** Total attempts a delivery gets before it is dead-lettered. */
    public int maxAttempts() {
        return schedule.size() + 1;
    }

    /**
     * @param attemptNo the 1-based attempt that just failed
     * @return how long to wait before the next attempt, or empty if there is none
     */
    public Optional<Duration> delayAfter(int attemptNo) {
        if (attemptNo < 1 || attemptNo > schedule.size()) {
            return Optional.empty();
        }
        Duration base = schedule.get(attemptNo - 1);
        long half = base.toMillis() / 2;
        long jitter = half == 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1);
        return Optional.of(Duration.ofMillis(half + jitter));
    }

    /** The undithered base delay, for tests and documentation. */
    public Optional<Duration> baseDelayAfter(int attemptNo) {
        if (attemptNo < 1 || attemptNo > schedule.size()) {
            return Optional.empty();
        }
        return Optional.of(schedule.get(attemptNo - 1));
    }
}
