package io.github.nexlayerlabs.cache;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Marks a method or class as cacheable.
 *
 * <p>When placed on a method, the return value is stored in Redis using a key
 * derived from the method name and parameters. Subsequent calls with the same
 * parameters return the cached value without executing the method body.
 *
 * <pre>{@code
 * @Cached(ttl = 300)
 * public User getUser(String id) {
 *     return userRepository.findById(id).orElseThrow();
 * }
 * }</pre>
 *
 * <p><b>Important:</b> The annotated method must be public and called via CDI
 * proxy (i.e. not a self-invocation within the same bean).
 */
@InterceptorBinding
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Cached {

    /**
     * Cache time-to-live in {@link #unit()} units.
     * Defaults to 300 seconds (5 minutes).
     */
    int ttl() default 300;

    /**
     * Time unit for {@link #ttl()}. Defaults to {@link TimeUnit#SECONDS}.
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * Logical cache name, used as the Redis key prefix.
     * Defaults to the method name if empty.
     *
     * <pre>{@code @Cached(ttl = 300, cacheName = "users") }</pre>
     */
    String cacheName() default "";

    /**
     * SpEL-style key expression. When provided, overrides the auto-generated key.
     * Use {@code #{#paramName}} to reference method parameters.
     *
     * <pre>{@code @Cached(ttl = 300, key = "#{#userId}:profile") }</pre>
     */
    String key() default "";

    /**
     * SpEL condition evaluated before caching. The method result is only cached
     * when this expression evaluates to {@code true}.
     *
     * <pre>{@code @Cached(ttl = 300, condition = "#id != null") }</pre>
     */
    String condition() default "";

    /**
     * When {@code true}, uses a distributed lock to ensure only one pod
     * reloads the cache on expiry. All other pods wait and then read from
     * the cache once the leader has refreshed it.
     *
     * <p>This prevents the "thundering herd" / cache stampede problem where
     * all 4 pods simultaneously query the database when a hot key expires.
     */
    boolean preventStampede() default false;

    /**
     * When {@code true} and Redis is unavailable, the method executes normally
     * without caching (graceful degradation). Defaults to {@code true}.
     */
    boolean fallback() default true;

    /**
     * When {@code true}, records hit/miss/error metrics via Micrometer.
     * Defaults to {@code true}.
     */
    boolean recordMetrics() default true;

    /**
     * Cache storage type. {@link CacheType#DISTRIBUTED} stores in Redis
     * (visible to all pods). {@link CacheType#LOCAL} stores only in the
     * current JVM (Caffeine-backed, not suitable for multi-pod setups).
     */
    CacheType type() default CacheType.DISTRIBUTED;

    /**
     * Supported cache storage backends.
     */
    enum CacheType {
        /** In-process cache. Fast but not shared across pods. */
        LOCAL,
        /** Redis-backed cache. Shared across all pods. */
        DISTRIBUTED
    }
}
