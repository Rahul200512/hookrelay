package io.github.rahul200512.hookrelay.tenancy;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anyone can create a tenant, which is what makes the live demo usable without an
 * account. It is also what would fill half a gigabyte of free-tier Postgres with other
 * people's test data, so demo tenants expire. Everything they own goes with them through
 * the foreign keys' cascade.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.delivery.poller-enabled", havingValue = "true", matchIfMissing = true)
public class DemoTenantPurge {

    private static final Logger log = LoggerFactory.getLogger(DemoTenantPurge.class);

    private final JdbcClient jdbc;
    private final Duration retention;
    private final Clock clock;

    public DemoTenantPurge(JdbcClient jdbc, HookrelayProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.retention = properties.security().demoRetention();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hookrelay.security.purge-interval:1h}",
            initialDelayString = "${hookrelay.security.purge-interval:1h}")
    @Transactional
    public void purgeExpiredDemoTenants() {
        int deleted = jdbc.sql("delete from tenants where is_demo and created_at < :cutoff")
                .param("cutoff", java.sql.Timestamp.from(clock.instant().minus(retention)))
                .update();
        if (deleted > 0) {
            log.info("purged {} demo tenants older than {}", deleted, retention);
        }
    }
}
