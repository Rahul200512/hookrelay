package io.github.rahul200512.hookrelay.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rahul200512.hookrelay.delivery.DeliveryQueue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The two properties the whole service rests on, measured rather than asserted in prose.
 *
 * <p>The background poller is switched off here so these tests drive {@link DeliveryQueue}
 * directly. With it running there would be a third, invisible worker competing for the
 * same rows, and "every row was claimed exactly once" would be a claim about a race we
 * could not see.
 */
@SpringBootTest(properties = {
        "hookrelay.delivery.poller-enabled=false",
        "hookrelay.delivery.lease=60s",
        "hookrelay.security.allow-private-targets=true",
        "logging.level.io.github.rahul200512.hookrelay=INFO",
})
@Testcontainers(disabledWithoutDocker = true)
class QueueGuaranteesIT {

    private static final int DELIVERIES = 10_000;
    private static final int WORKERS = 8;
    private static final int BATCH = 100;

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @Autowired
    DeliveryQueue queue;

    @Autowired
    JdbcClient jdbc;

    private UUID tenantId;
    private UUID endpointId;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from tenants").update();
        tenantId = jdbc.sql("insert into tenants (name) values ('queue-test') returning id")
                .query(UUID.class).single();
        endpointId = jdbc.sql("""
                        insert into endpoints (tenant_id, url, secret)
                        values (:tenant, 'https://example.invalid/hook', 'whsec_AAAA')
                        returning id""")
                .param("tenant", tenantId).query(UUID.class).single();
    }

    /**
     * Seeds due deliveries straight into the table. One event each, because a delivery is
     * unique per (event, endpoint) pair.
     */
    private void seed(int count) {
        jdbc.sql("""
                with new_events as (
                    insert into events (tenant_id, type, payload)
                    select :tenant, 'load.test', '{}'::jsonb from generate_series(1, :n)
                    returning id
                )
                insert into deliveries (event_id, endpoint_id, tenant_id, status, next_attempt_at)
                select id, :endpoint, :tenant, 'PENDING', now() from new_events
                """)
                .param("tenant", tenantId).param("endpoint", endpointId).param("n", count)
                .update();
    }

    @Test
    @DisplayName("10 000 due deliveries, 8 concurrent workers: every row claimed exactly once")
    void concurrentWorkersNeverClaimTheSameRowTwice() throws Exception {
        seed(DELIVERIES);

        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Callable<List<UUID>>> workers = IntStream.range(0, WORKERS)
                    .<Callable<List<UUID>>>mapToObj(w -> () -> {
                        List<UUID> mine = new ArrayList<>();
                        while (true) {
                            var claimed = queue.claim(BATCH);
                            if (claimed.isEmpty()) {
                                return mine;
                            }
                            claimed.forEach(c -> mine.add(c.id()));
                        }
                    })
                    .toList();

            long startedAt = System.nanoTime();
            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> future : pool.invokeAll(workers)) {
                all.addAll(future.get());
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            Set<UUID> distinct = new HashSet<>(all);
            // Printed so the figure in the README is one this test produced, not one typed in.
            System.out.printf("%nclaim: %d deliveries, %d workers, %d claims, %d distinct, %d duplicate, %d ms (%,d/s)%n%n",
                    DELIVERIES, WORKERS, all.size(), distinct.size(), all.size() - distinct.size(), elapsedMs,
                    elapsedMs == 0 ? 0 : (1000L * all.size()) / elapsedMs);
            assertThat(all).as("nothing was claimed twice").hasSameSizeAs(distinct);
            assertThat(distinct).as("nothing was left behind").hasSize(DELIVERIES);
        }

        // Everything is leased, so a ninth worker arriving now finds nothing to do.
        assertThat(queue.claim(BATCH)).isEmpty();
        assertThat(count("status = 'RUNNING'")).isEqualTo(DELIVERIES);
        assertThat(count("attempt_count <> 1")).isZero();
    }

    @Test
    @DisplayName("A worker that dies mid-delivery loses the attempt, not the delivery")
    void anExpiredLeaseReturnsTheRowToTheQueue() {
        seed(1);

        var first = queue.claim(BATCH);
        assertThat(first).hasSize(1);
        UUID deliveryId = first.get(0).id();
        assertThat(first.get(0).attemptNo()).isEqualTo(1);

        // While the lease is held the row is invisible: this is what stops two workers
        // from delivering the same event at the same time.
        assertThat(queue.claim(BATCH)).isEmpty();

        // The worker now dies. Nothing records an outcome, so the row stays RUNNING with a
        // lease nobody will ever release. Expiring it by hand is exactly what the clock
        // would do a minute later, without making the test wait a minute for it.
        jdbc.sql("update deliveries set lease_expires_at = now() - interval '1 second' where id = :id")
                .param("id", deliveryId).update();

        var second = queue.claim(BATCH);
        assertThat(second).as("the delivery came back").hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(deliveryId);
        assertThat(second.get(0).attemptNo()).as("the crashed attempt was counted, so a crash loop still ends").isEqualTo(2);

        assertThat(count("status = 'DEAD'")).as("nothing was lost").isZero();
        assertThat(count("1 = 1")).as("no duplicate row was created").isEqualTo(1);
    }

    private long count(String predicate) {
        return jdbc.sql("select count(*) from deliveries where " + predicate).query(Long.class).single();
    }
}
