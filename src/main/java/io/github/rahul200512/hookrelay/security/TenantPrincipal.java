package io.github.rahul200512.hookrelay.security;

import java.util.UUID;

public record TenantPrincipal(UUID tenantId, UUID apiKeyId) {}
