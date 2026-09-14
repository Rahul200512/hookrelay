package io.github.rahul200512.hookrelay.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    // The vector published with the Standard Webhooks spec.
    static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    static final long TS = 1614265330L;
    static final String BODY = "{\"test\": 2432232314}";
    static final String EXPECTED = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    @Test
    void matchesTheSpecTestVector() {
        assertThat(WebhookSigner.sign(SECRET, ID, TS, BODY)).isEqualTo(EXPECTED);
    }

    @Test
    void verifiesAgainstAHeaderWithSeveralSignatures() {
        assertThat(WebhookSigner.verify(SECRET, ID, TS, BODY, "v1,notit= " + EXPECTED)).isTrue();
        assertThat(WebhookSigner.verify(SECRET, ID, TS, BODY, "v1,notit=")).isFalse();
        assertThat(WebhookSigner.verify(SECRET, ID, TS + 1, BODY, EXPECTED)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, ID, TS, BODY + " ", EXPECTED)).isFalse();
    }

    @Test
    void newSecretsAre32RandomBytesWithThePrefix() {
        String a = WebhookSigner.newSecret();
        String b = WebhookSigner.newSecret();
        assertThat(a).startsWith("whsec_").isNotEqualTo(b);
        assertThat(java.util.Base64.getDecoder().decode(a.substring(6))).hasSize(32);
    }
}
