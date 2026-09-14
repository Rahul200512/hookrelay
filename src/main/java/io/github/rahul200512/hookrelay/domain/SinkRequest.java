package io.github.rahul200512.hookrelay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sink_requests")
public class SinkRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sink_id", nullable = false)
    private UUID sinkId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String headers;

    @Column(nullable = false)
    private String body;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected SinkRequest() {}

    public SinkRequest(UUID sinkId, String headers, String body, Instant receivedAt) {
        this.sinkId = sinkId;
        this.headers = headers;
        this.body = body;
        this.receivedAt = receivedAt;
    }

    public UUID getId() { return id; }
    public UUID getSinkId() { return sinkId; }
    public String getHeaders() { return headers; }
    public String getBody() { return body; }
    public Instant getReceivedAt() { return receivedAt; }
}
