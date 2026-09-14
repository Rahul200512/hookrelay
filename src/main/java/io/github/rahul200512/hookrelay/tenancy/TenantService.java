package io.github.rahul200512.hookrelay.tenancy;

import io.github.rahul200512.hookrelay.domain.ApiKey;
import io.github.rahul200512.hookrelay.domain.ApiKeyRepository;
import io.github.rahul200512.hookrelay.domain.Tenant;
import io.github.rahul200512.hookrelay.domain.TenantRepository;
import io.github.rahul200512.hookrelay.security.ApiKeys;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    public record Created(UUID tenantId, String name, String apiKey) {}

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;

    public TenantService(TenantRepository tenants, ApiKeyRepository apiKeys) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
    }

    @Transactional
    public Created create(String name) {
        Tenant tenant = tenants.save(new Tenant(name));
        ApiKeys.Generated key = ApiKeys.generate();
        apiKeys.save(new ApiKey(tenant.getId(), key.hash(), key.prefix()));
        return new Created(tenant.getId(), tenant.getName(), key.raw());
    }
}
