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
            boolean pollerEnabled,
            /** How delivery work is run. Switchable so the choice can be measured, not assumed. */
            @NotNull Threads threads,
            @NotNull Duration pauseCooldown,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Min(1) int pauseAfterConsecutiveFailures) {}

    /**
     * {@code VIRTUAL} gives every in-flight delivery its own thread; {@code PLATFORM}
     * runs them on a fixed pool of {@code platformPoolSize}, which is what this service
     * would have had to do before Java 21.
     */
    public record Threads(@NotNull Model model, @Min(1) int platformPoolSize) {
        public enum Model { VIRTUAL, PLATFORM }
    }

    public record Security(
            boolean allowPrivateTargets,
            @Min(1) int signupsPerHourPerIp,
            @Min(1) int eventsPerMinutePerTenant,
            /** Base64 of 32 bytes. Generate with: openssl rand -base64 32 */
            String encryptionKey,
            /** How long a rotated secret keeps signing alongside the new one. */
            @NotNull Duration secretOverlap,
            /** Demo tenants older than this are deleted. */
            @NotNull Duration demoRetention) {}
}
