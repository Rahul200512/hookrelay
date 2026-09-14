package io.github.rahul200512.hookrelay.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutcomeClassificationTest {

    @Test
    void only4xxThatWontChangeArePermanent() {
        assertThat(DeliveryOutcomes.isPermanentClientError(400)).isTrue();
        assertThat(DeliveryOutcomes.isPermanentClientError(404)).isTrue();
        assertThat(DeliveryOutcomes.isPermanentClientError(408)).isFalse();
        assertThat(DeliveryOutcomes.isPermanentClientError(425)).isFalse();
        assertThat(DeliveryOutcomes.isPermanentClientError(429)).isFalse();
        assertThat(DeliveryOutcomes.isPermanentClientError(500)).isFalse();
        assertThat(DeliveryOutcomes.isPermanentClientError(null)).isFalse();
    }

    @Test
    void anErrorWithoutAMessageStillSaysWhatHappened() {
        assertThat(DeliveryClient.describe(new java.net.ConnectException())).isEqualTo("ConnectException");
        assertThat(DeliveryClient.describe(new java.net.ConnectException("Host is down"))).isEqualTo("ConnectException: Host is down");
        // The useful part is the cause, never the wrapper.
        assertThat(DeliveryClient.describe(new RuntimeException("I/O error on POST",
                new java.net.SocketTimeoutException("read timed out")))).isEqualTo("SocketTimeoutException: read timed out");
    }

    @Test
    void retryAfterInSecondsIsHonouredAndDatesAreIgnored() {
        assertThat(DeliveryClient.parseRetryAfter("120")).contains(Duration.ofSeconds(120));
        assertThat(DeliveryClient.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isEmpty();
        assertThat(DeliveryClient.parseRetryAfter(null)).isEmpty();
    }
}
