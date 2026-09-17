package io.github.rahul200512.hookrelay.api;

import io.github.rahul200512.hookrelay.security.ClientIp;
import io.github.rahul200512.hookrelay.tenancy.RateLimits;
import io.github.rahul200512.hookrelay.tenancy.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantController {

    public record CreateTenantRequest(@NotBlank @Size(max = 100) String name) {}

    public record TenantCreated(UUID tenantId, String name, String apiKey, String note) {}

    private final TenantService tenants;
    private final RateLimits limits;

    public TenantController(TenantService tenants, RateLimits limits) {
        this.tenants = tenants;
        this.limits = limits;
    }

    @Operation(summary = "Create a tenant and get an API key", description = "Unauthenticated, rate limited per IP. The key is shown once.")
    @SecurityRequirements
    @PostMapping("/v1/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantCreated create(@Valid @RequestBody CreateTenantRequest body, HttpServletRequest request) {
        if (!limits.allowSignup(ClientIp.of(request))) {
            throw new ApiErrors.TooManyRequests("Too many tenants created from this address. Try again in an hour.");
        }
        var created = tenants.create(body.name().trim());
        return new TenantCreated(created.tenantId(), created.name(), created.apiKey(),
                "Store this key now; it is not shown again. Send it as 'Authorization: Bearer <key>'.");
    }
}
