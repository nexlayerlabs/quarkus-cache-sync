package io.github.nexlayerlabs.cache;

import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Configuration properties for Quarkus Pod Cache.
 *
 * <p>All properties are under the {@code quarkus.cache.sync} prefix.
 * Example {@code application.properties}:
 * <pre>
 * quarkus.cache.sync.host=redis-service
 * quarkus.cache.sync.port=6379
 * quarkus.cache.sync.default-ttl=300
 * quarkus.cache.sync.enable-metrics=true
 * quarkus.cache.sync.fallback-enabled=true
 * quarkus.cache.sync.warmup.enabled=true
 * </pre>
 */
@ConfigRoot
@ConfigMapping(prefix = "quarkus.cache.sync")
public interface CacheConfig {

    /** Redis hostname or service name (e.g. {@code redis-service} in Kubernetes). */
    @WithDefault("localhost")
    String host();

    /** Redis port. */
    @WithDefault("6379")
    int port();

    /** Redis password. Leave empty if Redis has no auth. */
    Optional<String> password();

    /** Redis logical database index (0–15). */
    @WithDefault("0")
    int db();

    /** Connection timeout in milliseconds. */
    @WithDefault("2000")
    int timeout();

    /** Default TTL in seconds applied when {@code @Cached} does not specify one. */
    @WithDefault("300")
    @WithName("default-ttl")
    int defaultTtl();

    /**
     * When {@code true}, records hit/miss/error counters via Micrometer.
     * Exposes metrics at {@code /metrics} (Prometheus) or {@code /q/metrics}.
     */
    @WithDefault("true")
    @WithName("enable-metrics")
    boolean enableMetrics();

    /**
     * When {@code true} and Redis is unreachable, cache operations are silently
     * skipped and the annotated method executes normally (no exception thrown).
     */
    @WithDefault("true")
    @WithName("fallback-enabled")
    boolean fallbackEnabled();

    /** Warmup sub-section. */
    WarmupConfig warmup();

    /** Configuration for pod-aware cache warmup. */
    interface WarmupConfig {

        /**
         * Enable the {@link CacheWarmup} feature.
         * When disabled, annotated warmup methods run like normal scheduled methods
         * (no distributed locking — every pod runs warmup independently).
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Redis key prefix used by ShedLock to store lock records.
         * Useful to namespace locks across multiple applications sharing Redis.
         */
        @WithDefault("shedlock:")
        @WithName("lock-prefix")
        String lockPrefix();
    }
}
