package io.github.rahul200512.hookrelay.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "hookrelay", version = "0.1",
                description = "At-least-once webhook delivery: signed (Standard Webhooks), retried with backoff, "
                        + "dead-lettered, replayable. Create a tenant to get an API key, then authorize with it."),
        security = @SecurityRequirement(name = "apiKey"))
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "The key returned by POST /v1/tenants, e.g. hr_live_...")
class OpenApiConfig {}
