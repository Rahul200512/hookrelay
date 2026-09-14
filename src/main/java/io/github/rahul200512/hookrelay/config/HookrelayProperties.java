package io.github.rahul200512.hookrelay.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "hookrelay")
public record HookrelayProperties(
        @NotNull String publicUrl,
        @NotNull Delivery delivery,
        @NotNull Security security) {

    public record Delivery(
            @NotNull Duration pollInterval,
            @Min(1) int batchSize,
            @Min(1) int maxInFlight,
            @Min(1) int perEndpointInFlight,
            @NotNull Duration lease,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Min(1) int pauseAfterConsecutiveFailures) {}

    public record Security(
            boolean allowPrivateTargets,
            @Min(1) int signupsPerHourPerIp) {}
}
