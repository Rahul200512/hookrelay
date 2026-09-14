package io.github.rahul200512.hookrelay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(List.of(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5)));

    @Test
    void oneMoreAttemptThanWaits() {
        assertThat(policy.maxAttempts()).isEqualTo(4);
    }

    @RepeatedTest(20)
    void jitterStaysWithinHalfToFullBase() {
        Duration d = policy.delayAfter(2).orElseThrow();
        assertThat(d).isBetween(Duration.ofSeconds(30), Duration.ofMinutes(1));
    }

    @Test
    void noDelayAfterTheLastWait() {
        assertThat(policy.delayAfter(3)).isPresent();
        assertThat(policy.delayAfter(4)).isEmpty();
        assertThat(policy.delayAfter(0)).isEmpty();
    }

    @Test
    void rejectsEmptySchedule() {
        assertThatThrownBy(() -> new BackoffPolicy(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
