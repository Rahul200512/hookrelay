package io.github.rahul200512.hookrelay.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignerMultiTest {

    private static final String ID = "d-1";
    private static final long TS = 1789400201L;
    private static final String BODY = "{\"a\":1}";

    @Test
    void aHeaderWithTwoSignaturesVerifiesUnderEitherSecret() {
        String oldSecret = WebhookSigner.newSecret();
        String newSecret = WebhookSigner.newSecret();
        String header = WebhookSigner.signAll(List.of(newSecret, oldSecret), ID, TS, BODY);

        assertThat(header.split(" ")).hasSize(2);
        assertThat(WebhookSigner.verify(newSecret, ID, TS, BODY, header)).isTrue();
        assertThat(WebhookSigner.verify(oldSecret, ID, TS, BODY, header)).isTrue();
        assertThat(WebhookSigner.verify(WebhookSigner.newSecret(), ID, TS, BODY, header)).isFalse();
    }

    @Test
    void oneSecretProducesTheSameHeaderAsSigningDirectly() {
        String secret = WebhookSigner.newSecret();
        assertThat(WebhookSigner.signAll(List.of(secret), ID, TS, BODY))
                .isEqualTo(WebhookSigner.sign(secret, ID, TS, BODY));
    }
}
