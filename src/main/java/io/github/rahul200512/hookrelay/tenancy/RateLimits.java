package io.github.rahul200512.hookrelay.tenancy;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The two limits the service enforces: who may sign up, and how fast a tenant may publish. */
@Component
public class RateLimits {

    private final FixedWindowLimiter signups;
    private final FixedWindowLimiter events;

    public RateLimits(HookrelayProperties properties, Clock clock) {
        this.signups = new FixedWindowLimiter(properties.security().signupsPerHourPerIp(), Duration.ofHours(1), clock);
        this.events = new FixedWindowLimiter(properties.security().eventsPerMinutePerTenant(), Duration.ofMinutes(1), clock);
    }

    public boolean allowSignup(String ip) {
        return signups.allow(ip);
    }

    public boolean allowEvent(UUID tenantId) {
        return events.allow(tenantId.toString());
    }
}
