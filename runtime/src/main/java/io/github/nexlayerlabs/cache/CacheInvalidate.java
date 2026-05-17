package io.github.nexlayerlabs.cache;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Evicts one or more named caches after the annotated method completes
 * successfully.
 *
 * <p>When {@link #broadcastToPods()} is {@code true} (default), the eviction
 * event is published to a Redis Pub/Sub channel so all pods invalidate their
 * local state in sync.
 *
 * <pre>{@code
 * @CacheInvalidate(names = {"user", "user-orders"}, broadcastToPods = true)
 * public User updateUser(String id, UserUpdate update) {
 *     return userRepository.save(id, update);
 * }
 * }</pre>
 */
@InterceptorBinding
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CacheInvalidate {

    /**
     * Names of the caches to invalidate. All Redis keys with these prefixes
     * are deleted after the method completes.
     */
    String[] names() default {};

    /**
     * When {@code true}, publishes a Redis Pub/Sub event so every pod
     * in the cluster also invalidates its copy. Defaults to {@code true}.
     *
     * <p>This is the key feature that prevents stale data across pods:
     * <pre>
     *   Pod 1 → updates DB → invalidates its own Redis keys
     *                      → publishes "cache:invalidation" event
     *   Pod 2 → receives event → invalidates its own local cache
     *   Pod 3 → receives event → invalidates its own local cache
     *   Pod 4 → receives event → invalidates its own local cache
     * </pre>
     */
    boolean broadcastToPods() default true;

    /**
     * Cache keys in other services to invalidate via HTTP event.
     * Format: {@code "service-name:cache-name"}.
     *
     * <pre>{@code
     * externalCaches = {"notification-service:user-prefs"}
     * }</pre>
     */
    String[] externalCaches() default {};
}
