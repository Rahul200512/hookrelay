package io.github.rahul200512.hookrelay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "endpoints")
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String url;

    private String description;

    @Column(nullable = false)
    private String secret;

    @Column(name = "previous_secret")
    private String previousSecret;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    /** null means "every event type". */
    @Column(name = "event_types", columnDefinition = "text[]")
    private String[] eventTypes;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "paused_reason")
    private String pausedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Endpoint() {}

    public Endpoint(UUID tenantId, String url, String description, String secret, String[] eventTypes) {
        this.tenantId = tenantId;
        this.url = url;
        this.description = description;
        this.secret = secret;
        this.eventTypes = eventTypes;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean accepts(String eventType) {
        return eventTypes == null || eventTypes.length == 0 || Arrays.asList(eventTypes).contains(eventType);
    }

    public boolean isDeliverable() {
        return enabled && pausedAt == null;
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
    }

    /** @return true if this failure crossed the pause threshold. */
    public boolean recordFailure(int pauseAfter, Instant now) {
        consecutiveFailures++;
        if (pausedAt == null && consecutiveFailures >= pauseAfter) {
            pausedAt = now;
            pausedReason = consecutiveFailures + " consecutive failed deliveries";
            return true;
        }
        return false;
    }

    public void disable(String reason, Instant now) {
        enabled = false;
        pausedAt = now;
        pausedReason = reason;
    }

    public void enable() {
        enabled = true;
        pausedAt = null;
        pausedReason = null;
        consecutiveFailures = 0;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSecret() { return secret; }
    public String getPreviousSecret() { return previousSecret; }
    public Instant getPreviousSecretExpiresAt() { return previousSecretExpiresAt; }
    public String[] getEventTypes() { return eventTypes; }
    public void setEventTypes(String[] eventTypes) { this.eventTypes = eventTypes; }
    public boolean isEnabled() { return enabled; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public Instant getPausedAt() { return pausedAt; }
    public String getPausedReason() { return pausedReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
