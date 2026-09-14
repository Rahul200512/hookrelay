package io.github.rahul200512.hookrelay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "status_code")
    private Integer statusCode;

    private String error;

    protected DeliveryAttempt() {}

    public DeliveryAttempt(UUID deliveryId, int attemptNo, Instant startedAt, int durationMs, Integer statusCode, String error) {
        this.deliveryId = deliveryId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.statusCode = statusCode;
        this.error = error;
    }

    public UUID getId() { return id; }
    public UUID getDeliveryId() { return deliveryId; }
    public int getAttemptNo() { return attemptNo; }
    public Instant getStartedAt() { return startedAt; }
    public int getDurationMs() { return durationMs; }
    public Integer getStatusCode() { return statusCode; }
    public String getError() { return error; }
}
