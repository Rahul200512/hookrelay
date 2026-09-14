package io.github.rahul200512.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    private static SsrfGuard guard(boolean allowPrivate) {
        var delivery = new HookrelayProperties.Delivery(Duration.ofSeconds(1), 1, 1, 1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        return new SsrfGuard(new HookrelayProperties("http://localhost", delivery, new HookrelayProperties.Security(allowPrivate)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://1.1.1.1/hook",             // https required
            "https://127.0.0.1/hook",          // loopback
            "https://localhost/hook",
            "https://10.1.2.3/hook",           // rfc1918
            "https://172.16.0.9/hook",
            "https://192.168.1.1/hook",
            "https://169.254.169.254/latest",  // cloud metadata
            "https://100.64.0.1/hook",         // cgnat
            "https://0.0.0.0/hook",
            "https://[::1]/hook",
            "https://[fd00::1]/hook",          // ipv6 unique local
            "https://user:pw@1.1.1.1/hook",    // embedded credentials
            "https:///hook",
            "not a url at all",
    })
    void rejectsPrivateAndMalformedTargets(String url) {
        assertThatThrownBy(() -> guard(false).validate(url)).isInstanceOf(SsrfGuard.ForbiddenTargetException.class);
    }

    @Test
    void acceptsAPublicHttpsAddress() {
        assertThat(guard(false).validate("https://1.1.1.1/hook").getHost()).isEqualTo("1.1.1.1");
    }

    @Test
    void devModeAllowsLocalHttp() {
        assertThat(guard(true).validate("http://localhost:8080/sink/x").getPort()).isEqualTo(8080);
        assertThatThrownBy(() -> guard(true).validate("ftp://localhost/x")).isInstanceOf(SsrfGuard.ForbiddenTargetException.class);
    }

    @Test
    void privateClassifier() throws Exception {
        assertThat(SsrfGuard.isPrivate(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(SsrfGuard.isPrivate(InetAddress.getByName("240.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isPrivate(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }
}
