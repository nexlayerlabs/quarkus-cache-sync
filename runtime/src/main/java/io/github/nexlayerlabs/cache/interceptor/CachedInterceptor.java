package io.github.nexlayerlabs.cache.interceptor;

import io.github.nexlayerlabs.cache.Cached;
import io.github.nexlayerlabs.cache.CacheConfig;
import io.github.nexlayerlabs.cache.metrics.CacheMetricsCollector;
import io.github.nexlayerlabs.cache.redis.RedisCacheManager;
import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CDI interceptor that backs the {@link Cached} annotation.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Generate a Redis key from method name + parameters (or custom SpEL key).</li>
 *   <li>Evaluate {@code @Cached#condition()} — skip caching if false.</li>
 *   <li>Attempt Redis GET. On hit, record metric and return immediately.</li>
 *   <li>On miss: if {@code preventStampede=true}, acquire a JVM-level lock before
 *       executing the method (double-checked). Release lock after Redis SET.</li>
 *   <li>Execute the real method, store result in Redis with TTL, return result.</li>
 *   <li>On any Redis error: if {@code fallback=true} execute the method normally;
 *       otherwise re-throw.</li>
 * </ol>
 */
@Interceptor
@Cached
@Priority(Interceptor.Priority.PLATFORM_AFTER)
@ApplicationScoped
public class CachedInterceptor {

    /** JVM-local lock used when {@code preventStampede=true}. */
    private static final ReentrantLock STAMPEDE_LOCK = new ReentrantLock();

    @Inject
    RedisCacheManager cacheManager;

    @Inject
    CacheMetricsCollector metrics;

    @Inject
    CacheConfig config;

    @AroundInvoke
    public Object intercept(final InvocationContext ctx) throws Exception {
        final Cached annotation = resolveAnnotation(ctx);
        if (annotation == null) {
            return ctx.proceed();
        }

        final String cacheKey = buildKey(ctx, annotation);

        // Conditional caching
        if (!evaluateCondition(annotation.condition(), ctx)) {
            return ctx.proceed();
        }

        try {
            // --- Cache HIT ---
            final Optional<Object> hit = cacheManager.get(cacheKey, annotation.type());
            if (hit.isPresent()) {
                if (annotation.recordMetrics()) {
                    metrics.recordHit(cacheKey);
                }
                Log.debugf("[CacheSync] HIT  key=%s", cacheKey);
                return hit.get();
            }

            // --- Cache MISS ---
            if (annotation.recordMetrics()) {
                metrics.recordMiss(cacheKey);
            }
            Log.debugf("[CacheSync] MISS key=%s", cacheKey);

            final Object result;

            if (annotation.preventStampede()) {
                result = executeWithStampedeLock(ctx, cacheKey, annotation);
            } else {
                result = ctx.proceed();
                storeIfSerializable(result, cacheKey, annotation);
            }

            return result;

        } catch (Exception e) {
            if (annotation.fallback()) {
                Log.warnf("[CacheSync] Redis error (fallback=true), executing method "
                        + "without cache. key=%s error=%s", cacheKey, e.getMessage());
                return ctx.proceed();
            }
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Object executeWithStampedeLock(final InvocationContext ctx,
                                            final String cacheKey,
                                            final Cached annotation) throws Exception {
        STAMPEDE_LOCK.lock();
        try {
            // Double-check: another thread may have populated the cache while we waited
            final Optional<Object> doubleCheck = cacheManager.get(cacheKey, annotation.type());
            if (doubleCheck.isPresent()) {
                Log.debugf("[CacheSync] Stampede double-check HIT key=%s", cacheKey);
                return doubleCheck.get();
            }
            final Object result = ctx.proceed();
            storeIfSerializable(result, cacheKey, annotation);
            return result;
        } finally {
            STAMPEDE_LOCK.unlock();
        }
    }

    private void storeIfSerializable(final Object result,
                                      final String cacheKey,
                                      final Cached annotation) {
        if (result instanceof Serializable) {
            final long ttlSeconds = annotation.unit().toSeconds(annotation.ttl());
            cacheManager.set(cacheKey, result, ttlSeconds, annotation.type());
        } else if (result != null) {
            Log.warnf("[CacheSync] Result of type %s is not Serializable — not cached. key=%s",
                    result.getClass().getName(), cacheKey);
        }
    }

    /** Resolves {@link Cached} from method first, then class. */
    private Cached resolveAnnotation(final InvocationContext ctx) {
        final Method method = ctx.getMethod();
        final Cached fromMethod = method.getAnnotation(Cached.class);
        if (fromMethod != null) {
            return fromMethod;
        }
        return method.getDeclaringClass().getAnnotation(Cached.class);
    }

    /**
     * Builds a Redis key.
     * <ul>
     *   <li>If {@code @Cached#key()} is set, evaluates it as a simple template.</li>
     *   <li>Otherwise: {@code <cacheName>:<param0>:<param1>:...}</li>
     * </ul>
     */
    private String buildKey(final InvocationContext ctx, final Cached annotation) {
        if (!annotation.key().isEmpty()) {
            return evaluateKeyExpression(annotation.key(), ctx);
        }

        final String prefix = annotation.cacheName().isEmpty()
                ? ctx.getMethod().getName()
                : annotation.cacheName();

        final StringBuilder sb = new StringBuilder(prefix);
        for (final Object param : ctx.getParameters()) {
            sb.append(':').append(param != null ? param : "null");
        }
        return sb.toString();
    }

    /**
     * Minimal key expression: replaces {@code #{#id}}, {@code #{#userId}}, etc.
     * with the first method parameter's {@code toString()}.
     */
    private String evaluateKeyExpression(final String expression,
                                          final InvocationContext ctx) {
        String result = expression;
        final Object[] params = ctx.getParameters();
        final String[] paramNames = {"id", "userId", "orderId", "productId",
                "paymentId", "key", "name"};
        for (int i = 0; i < Math.min(params.length, paramNames.length); i++) {
            if (params[i] != null) {
                result = result.replace("#{#" + paramNames[i] + "}", params[i].toString());
            }
        }
        return result;
    }

    /** Returns {@code true} if condition is blank (default) or evaluates truthy. */
    private boolean evaluateCondition(final String condition,
                                       final InvocationContext ctx) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        // Simple null-check condition: "#id != null"
        if (condition.contains("!= null")) {
            final Object[] params = ctx.getParameters();
            return params.length > 0 && params[0] != null;
        }
        return true;
    }
}
