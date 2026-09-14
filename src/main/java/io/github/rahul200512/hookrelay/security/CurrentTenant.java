package io.github.rahul200512.hookrelay.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentTenant {
    private CurrentTenant() {}

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantPrincipal principal) {
            return principal.tenantId();
        }
        throw new IllegalStateException("no authenticated tenant");
    }
}
