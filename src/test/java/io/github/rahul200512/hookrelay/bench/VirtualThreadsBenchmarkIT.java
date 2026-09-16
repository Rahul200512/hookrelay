package io.github.rahul200512.hookrelay.bench;

import io.github.rahul200512.hookrelay.it.FakeReceiver;
import io.github.rahul200512.hookrelay.it.TestKeys;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every in-flight delivery gets its own thread, so the in-flight cap is the only limit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hookrelay.delivery.threads.model=VIRTUAL",
        "hookrelay.delivery.max-in-flight=256",
        "hookrelay.delivery.per-endpoint-in-flight=256",
        "hookrelay.delivery.batch-size=256",
        "hookrelay.delivery.poll-interval=50ms",
        "hookrelay.security.allow-private-targets=true",
        "spring.datasource.hikari.maximum-pool-size=16",
        "logging.level.io.github.rahul200512.hookrelay=WARN",
})
@Testcontainers(disabledWithoutDocker = true)
@Import({FakeReceiver.class, FakeReceiver.ReachableFromOutside.class})
class VirtualThreadsBenchmarkIT extends ThroughputBenchmark {

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @Override
    String label() {
        return "virtual";
    }
}
