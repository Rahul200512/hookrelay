package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.domain.ApiKey;
import io.github.rahul200512.hookrelay.domain.ApiKeyRepository;
import io.github.rahul200512.hookrelay.security.ApiKeys;
import io.github.rahul200512.hookrelay.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keys are created and revoked separately so a rotation never has a gap: issue the new
 * key, deploy it, then revoke the old one. Only the prefix is ever readable afterwards,
 * because only the SHA-256 is stored.
 */
@RestController
public class ApiKeyController {

    static final int MAX_ACTIVE_KEYS = 5;

    public record ApiKeyResponse(UUID id, String prefix, Instant createdAt, Instant revokedAt) {
        static ApiKeyResponse of(ApiKey key) {
            return new ApiKeyResponse(key.getId(), key.getPrefix(), key.getCreatedAt(), key.getRevokedAt());
        }
    }

    public record ApiKeyCreated(ApiKeyResponse key, String apiKey, String note) {}

    private final ApiKeyRepository apiKeys;
    private final Clock clock;

    public ApiKeyController(ApiKeyRepository apiKeys, Clock clock) {
        this.apiKeys = apiKeys;
        this.clock = clock;
    }

    @Operation(summary = "Issue another API key", description = "Shown once. Use it to rotate without downtime.")
    @PostMapping("/v1/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiKeyCreated create() {
        UUID tenantId = CurrentTenant.id();
        if (apiKeys.countByTenantIdAndRevokedAtIsNull(tenantId) >= MAX_ACTIVE_KEYS) {
            throw new ApiErrors.LimitReached("A tenant may have at most " + MAX_ACTIVE_KEYS
                    + " active keys. Revoke one first.");
        }
        ApiKeys.Generated generated = ApiKeys.generate();
        ApiKey saved = apiKeys.save(new ApiKey(tenantId, generated.hash(), generated.prefix()));
        return new ApiKeyCreated(ApiKeyResponse.of(saved), generated.raw(), "Store this now; it is not shown again.");
    }

    @Operation(summary = "List this tenant's keys", description = "Prefixes only. The keys themselves are not stored.")
    @GetMapping("/v1/api-keys")
    public List<ApiKeyResponse> list() {
        return apiKeys.findByTenantIdOrderByCreatedAtDesc(CurrentTenant.id()).stream().map(ApiKeyResponse::of).toList();
    }

    @Operation(summary = "Revoke a key",
            description = "Takes effect on the next request. Revoking your only active key is refused, "
                    + "because it would lock the tenant out with no way back in.")
    @DeleteMapping("/v1/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@PathVariable UUID id) {
        UUID tenantId = CurrentTenant.id();
        ApiKey key = apiKeys.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new ApiErrors.NotFound("API key"));
        if (key.isRevoked()) {
            return;
        }
        if (apiKeys.countByTenantIdAndRevokedAtIsNull(tenantId) <= 1) {
            throw new ApiErrors.LimitReached("This is the tenant's only active key. "
                    + "Issue a replacement before revoking it, or there is no way back in.");
        }
        key.revoke(clock.instant());
        apiKeys.save(key);
    }
}
