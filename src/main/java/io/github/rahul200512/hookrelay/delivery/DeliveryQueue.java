package io.github.rahul200512.hookrelay.delivery;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queue is a table. Claiming is one statement: lock a batch of due rows, skipping
 * rows another worker holds, mark them RUNNING with a lease, and count the attempt.
 *
 * <p>Two workers can never claim the same row ({@code FOR UPDATE SKIP LOCKED}), and a
 * worker that dies mid-delivery loses nothing: when its lease lapses the row is due
 * again. Counting the attempt at claim time, not at completion, means a worker that
 * crashes on the same row forever still runs out of attempts.
 */
@Component
public class DeliveryQueue {

    public record Claimed(UUID id, UUID eventId, UUID endpointId, int attemptNo) {}

    private static final String CLAIM = """
            UPDATE deliveries
               SET status = 'RUNNING',
                   attempt_count = attempt_count + 1,
                   lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                   updated_at = now()
             WHERE id IN (SELECT id
                            FROM deliveries
                           WHERE (status = 'PENDING' AND next_attempt_at <= now())
                              OR (status = 'RUNNING' AND lease_expires_at < now())
                           ORDER BY next_attempt_at
                           FOR UPDATE SKIP LOCKED
                           LIMIT :batch)
            RETURNING id, event_id, endpoint_id, attempt_count
            """;

    private final JdbcClient jdbc;
    private final long leaseSeconds;

    public DeliveryQueue(JdbcClient jdbc, HookrelayProperties properties) {
        this.jdbc = jdbc;
        this.leaseSeconds = properties.delivery().lease().toSeconds();
    }

    @Transactional
    public List<Claimed> claim(int batch) {
        if (batch <= 0) {
            return List.of();
        }
        return jdbc.sql(CLAIM)
                .param("leaseSeconds", leaseSeconds)
                .param("batch", batch)
                .query((rs, i) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getInt("attempt_count")))
                .list();
    }
}
