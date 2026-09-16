package io.github.rahul200512.hookrelay.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.rahul200512.hookrelay.delivery.DeliveryDispatcher;
import io.github.rahul200512.hookrelay.it.FakeReceiver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the thread model is actually worth on this workload.
 *
 * <p>Excluded from {@code mvn verify} because it takes a minute and measures a machine as
 * much as it measures the code. Run it deliberately:
 *
 * <pre>
 *   ./mvnw test -Dtest='*BenchmarkIT'
 * </pre>
 *
 * <p>Each subclass boots the same application with the thread model changed and nothing
 * else. The receiver is in this process and sleeps for {@link #RECEIVER_DELAY_MS}, so
 * what is being measured is how many deliveries can be in flight at once — which is the
 * whole question, because a delivery spends nearly all of its life waiting for someone
 * else's server to answer.
 */
@Tag("benchmark")
abstract class ThroughputBenchmark {

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

    @Autowired
    DeliveryDispatcher dispatcher;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSource;

    abstract String label();

    @Test
    @DisplayName("500 deliveries to a receiver that takes 200 ms")
    void run() {
        receiver.script("bench", 200);
        receiver.delay("bench", RECEIVER_DELAY_MS);

        // Both benchmarks share one container, so the previous run's rows are still here.
        // Without this the second one finds 500 already-succeeded deliveries and "finishes"
        // in a few hundred milliseconds, which is how a benchmark comes to report a number
        // that is physically impossible for the work it claims to have done.
        jdbc.sql("delete from tenants").update();

        UUID tenantId = jdbc.sql("insert into tenants (name) values ('bench') returning id")
                .query(UUID.class).single();
        UUID endpointId = jdbc.sql("""
                        insert into endpoints (tenant_id, url, secret)
                        values (:tenant, :url, :secret) returning id""")
                .param("tenant", tenantId)
                .param("url", "http://localhost:" + port + "/fake/bench")
                // Written through JDBC, so it has to be in the stored form the converter reads back.
                .param("secret", encryptedBenchSecret())
                .query(UUID.class).single();

        // Sample how many deliveries are actually in flight: a throughput number nobody
        // can account for is not a measurement, it is a coincidence.
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        int capacity = dispatcher.capacity();
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(capacity - dispatcher.capacity(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

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
                .param("tenant", tenantId).param("endpoint", endpointId).param("n", DELIVERIES)
                .update();

        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(100)).until(() ->
                jdbc.sql("select count(*) from deliveries where tenant_id = :t and status = 'SUCCEEDED'")
                        .param("t", tenantId).query(Long.class).single() == DELIVERIES);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        sampler.interrupt();

        List<Integer> codes = jdbc.sql("""
                select distinct a.status_code from delivery_attempts a
                  join deliveries d on d.id = a.delivery_id
                 where d.tenant_id = :t""")
                .param("t", tenantId).query(Integer.class).list();
        assertThat(codes).as("every delivery succeeded first time, so this measures throughput not retries")
                .containsExactly(200);

        // Where the time went, from the attempt log the service writes anyway.
        var call = jdbc.sql("""
                select round(avg(a.duration_ms))                                          as avg_ms,
                       max(a.duration_ms)                                                 as max_ms,
                       round(extract(epoch from max(a.started_at) - min(a.started_at)) * 1000) as spread_ms
                  from delivery_attempts a join deliveries d on d.id = a.delivery_id
                 where d.tenant_id = :t""")
                .param("t", tenantId)
                .query((rs, i) -> new long[] {rs.getLong("avg_ms"), rs.getLong("max_ms"), rs.getLong("spread_ms")})
                .single();

        System.out.printf("%nbench  %-14s %d deliveries in %,d ms = %.0f/s | call avg %d ms, max %d ms | "
                        + "dispatch spread %,d ms | admitted %d of %d | receiver %d ms%n%n",
                label(), DELIVERIES, elapsedMs, 1000.0 * DELIVERIES / elapsedMs,
                call[0], call[1], call[2], peak.get(), capacity, RECEIVER_DELAY_MS);
    }

    @Autowired
    io.github.rahul200512.hookrelay.crypto.SecretCrypto crypto;

    private String encryptedBenchSecret() {
        return crypto.encrypt(io.github.rahul200512.hookrelay.domain.WebhookSigner.newSecret());
    }
}
