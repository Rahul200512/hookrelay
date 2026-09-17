package io.github.rahul200512.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

    private static MockHttpServletRequest request(String remoteAddr) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void withNoProxyItIsTheSocketAddress() {
        assertThat(ClientIp.of(request("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void theEdgeHeaderWinsBecauseTheEdgeRewritesItEveryTime() {
        var r = request("10.0.0.1");
        r.addHeader("CF-Connecting-IP", "203.0.113.7");
        r.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
        assertThat(ClientIp.of(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void otherwiseTheLeftmostForwardedEntryIsTheClient() {
        var r = request("10.0.0.1");
        r.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 10.0.0.1");
        assertThat(ClientIp.of(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void whitespaceAndSingleEntryFormsBothWork() {
        var r = request("10.0.0.1");
        r.addHeader("X-Forwarded-For", "  203.0.113.7  ");
        assertThat(ClientIp.of(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void emptyHeadersFallThroughRatherThanKeyingOnNothing() {
        // The bug this exists to prevent: every caller sharing one key, or each getting a
        // unique one, both turn a limiter into decoration.
        var r = request("10.0.0.1");
        r.addHeader("CF-Connecting-IP", "");
        r.addHeader("X-Forwarded-For", "   ");
        assertThat(ClientIp.of(r)).isEqualTo("10.0.0.1");
    }

    @Test
    void neverReturnsNull() {
        assertThat(ClientIp.of(request(null))).isEqualTo("unknown");
    }
}
