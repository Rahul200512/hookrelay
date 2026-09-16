package io.github.rahul200512.hookrelay.bench;

import io.github.rahul200512.hookrelay.it.FakeReceiver;
import io.github.rahul200512.hookrelay.it.TestKeys;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A fixed pool of 16, which is about what you would dare give a 512 MB container before
 * Java 21: a platform thread costs a megabyte of stack whether it is working or waiting,
 * and in this workload they are almost always waiting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hookrelay.delivery.threads.model=PLATFORM",
        "hookrelay.delivery.threads.platform-pool-size=16",
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
class PlatformPoolBenchmarkIT extends ThroughputBenchmark {

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @Override
    String label() {
        return "platform x16";
    }
}
