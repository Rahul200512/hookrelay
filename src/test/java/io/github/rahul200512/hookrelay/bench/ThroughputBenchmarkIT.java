package io.github.rahul200512.hookrelay.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.rahul200512.hookrelay.it.FakeReceiver;
import io.github.rahul200512.hookrelay.it.TestKeys;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the thread model is worth on this workload.
 *
 * <p>Excluded from {@code mvn verify} because it takes a minute and measures a machine as
 * much as it measures the code. Run it deliberately:
 *
 * <pre>
 *   ./mvnw test -Dtest=ThroughputBenchmarkIT -Dgroups=benchmark
 * </pre>
 *
 * <p>Each subclass boots the same application with one property different. The receiver
 * is in this process and sleeps for {@link #RECEIVER_DELAY_MS}, so what is being measured
 * is how many deliveries the dispatcher can have waiting at once, which is the whole
 * question: a delivery spends nearly all its life waiting for somebody else's server.
 */
@Tag("benchmark")
abstract class ThroughputBenchmarkIT {

    static final int DELIVERIES = 500;
    static final long RECEIVER_DELAY_MS = 200;

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeReceiver receiver;

    abstract String label();

    @Test
    @DisplayName("500 deliveries to a receiver that takes 200 ms")
    void run() {
        receiver.script("bench", 200);
        receiver.delay("bench", RECEIVER_DELAY_MS);

        UUID tenantId = jdbc.sql("insert into tenants (name) values ('bench') returning id").query(UUID.class).single();
        UUID endpointId = jdbc.sql("""
                        insert into endpoints (tenant_id, url, secret)
                        values (:tenant, :url, 'v1:unused-by-this-path') returning id""")
                .param("tenant", tenantId)
                .param("url", "http://localhost:" + port + "/fake/bench")
                .query(UUID.class).single();

        long startedAt = System.nanoTime();
        jdbc.sql("""
                with new_events as (
                    insert into events (tenant_id, type, payload)
                    select :tenant, 'bench', '{}'::jsonb from generate_series(1, :n)
                    returning id
                )
                insert into deliveries (event_id, endpoint_id, tenant_id, status, next_attempt_at)
                select id, :endpoint, :tenant, 'PENDING', now() from new_events
                """)
                .param("tenant", tenantId).param("endpoint", endpointId).param("n", DELIVERIES).update();

        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(200)).until(() ->
                jdbc.sql("select count(*) from deliveries where status = 'SUCCEEDED'").query(Long.class).single() == DELIVERIES);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<Integer> codes = jdbc.sql("select distinct status_code from delivery_attempts").query(Integer.class).list();
        assertThat(codes).containsExactly(200);

        System.out.printf("%nbench %-18s %d deliveries, %d ms wall, %.0f/s, receiver latency %d ms%n%n",
                label(), DELIVERIES, elapsedMs, 1000.0 * DELIVERIES / elapsedMs, RECEIVER_DELAY_MS);
    }

    /** Every in-flight delivery gets its own thread; the in-flight cap is the only limit. */
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
    static class VirtualThreads extends ThroughputBenchmarkIT {
        @DynamicPropertySource
        static void encryptionKey(DynamicPropertyRegistry registry) {
            TestKeys.register(registry);
        }

        @Override
        String label() {
            return "virtual";
        }
    }

    /**
     * A fixed pool of 16, which is about what you would dare give a 512 MB container
     * before Java 21: a platform thread costs a megabyte of stack whether it is working
     * or waiting, and here they are all waiting.
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
    static class PlatformPool extends ThroughputBenchmarkIT {
        @DynamicPropertySource
        static void encryptionKey(DynamicPropertyRegistry registry) {
            TestKeys.register(registry);
        }

        @Override
        String label() {
            return "platform x16";
        }
    }
}
