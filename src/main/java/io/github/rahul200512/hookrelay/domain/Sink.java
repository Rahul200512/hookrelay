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
@Table(name = "sinks")
public class Sink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String token;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Sink() {}

    public Sink(UUID tenantId, String token) {
        this.tenantId = tenantId;
        this.token = token;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getToken() { return token; }
    public Instant getCreatedAt() { return createdAt; }
}
