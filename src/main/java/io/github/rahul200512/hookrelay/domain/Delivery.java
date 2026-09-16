package io.github.rahul200512.hookrelay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * The attempt count when this delivery was last replayed. The retry budget is
     * counted from here, so a replay starts over without ever reusing an attempt
     * number: the attempt log is append-only and keeps every round.
     */
    @Column(name = "attempts_at_replay", nullable = false)
    private int attemptsAtReplay;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Delivery() {}

    public Delivery(Event event, Endpoint endpoint, Instant nextAttemptAt) {
        this.eventId = event.getId();
        this.endpointId = endpoint.getId();
        this.tenantId = event.getTenantId();
        this.nextAttemptAt = nextAttemptAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void succeeded(int statusCode) {
        status = DeliveryStatus.SUCCEEDED;
        lastStatusCode = statusCode;
        lastError = null;
        leaseExpiresAt = null;
    }

    public void retryAt(Instant when, Integer statusCode, String error) {
        status = DeliveryStatus.PENDING;
        nextAttemptAt = when;
        lastStatusCode = statusCode;
        lastError = error;
        leaseExpiresAt = null;
    }

    public void dead(Integer statusCode, String error) {
        status = DeliveryStatus.DEAD;
        lastStatusCode = statusCode;
        lastError = error;
        leaseExpiresAt = null;
    }

    /** Puts a finished delivery back on the queue with a fresh budget. */
    public void replay(Instant now) {
        status = DeliveryStatus.PENDING;
        nextAttemptAt = now;
        leaseExpiresAt = null;
        attemptsAtReplay = attemptCount;
        lastStatusCode = null;
        lastError = null;
    }

    /** Which attempt of the current round this is: 1 on a fresh delivery. */
    public int attemptInRound(int attemptNo) {
        return attemptNo - attemptsAtReplay;
    }

    public boolean isReplayable() {
        return status == DeliveryStatus.DEAD || status == DeliveryStatus.SUCCEEDED;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getEndpointId() { return endpointId; }
    public UUID getTenantId() { return tenantId; }
    public DeliveryStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getAttemptsAtReplay() { return attemptsAtReplay; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Integer getLastStatusCode() { return lastStatusCode; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
