package io.github.nexlayerlabs.cache.interceptor;

import io.github.nexlayerlabs.cache.CacheInvalidate;
import io.github.nexlayerlabs.cache.redis.RedisCacheManager;
import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Method;

/**
 * CDI interceptor that backs the {@link CacheInvalidate} annotation.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Execute the real method first (write-to-DB succeeds or throws).</li>
 *   <li>For each name in {@code @CacheInvalidate#names()}, delete all matching
 *       Redis keys.</li>
 *   <li>If {@code broadcastToPods=true}, publish a Pub/Sub event so other pods
 *       also invalidate their copies.</li>
 *   <li>For each entry in {@code externalCaches}, notify the external service.</li>
 * </ol>
 *
 * <p>Invalidation failures are logged as warnings but never swallow the
 * result of the business method — your update always completes even if
 * Redis eviction fails.
 */
@Interceptor
@CacheInvalidate
@Priority(Interceptor.Priority.PLATFORM_AFTER + 1)
@ApplicationScoped
public class CacheInvalidationInterceptor {

    @Inject
    RedisCacheManager cacheManager;

    @AroundInvoke
    public Object intercept(final InvocationContext ctx) throws Exception {
        final CacheInvalidate annotation = resolveAnnotation(ctx);
        if (annotation == null) {
            return ctx.proceed();
        }

        // Execute the business method first
        final Object result = ctx.proceed();

        // Invalidate named caches
        for (final String cacheName : annotation.names()) {
            try {
                cacheManager.invalidate(cacheName);

                if (annotation.broadcastToPods()) {
                    cacheManager.publishInvalidationEvent(cacheName);
                }

            } catch (Exception e) {
                Log.warnf("[CacheSync] Failed to invalidate cache=%s : %s",
                        cacheName, e.getMessage());
            }
        }

        // Notify external services
        for (final String externalCache : annotation.externalCaches()) {
            try {
                cacheManager.notifyExternalService(externalCache);
            } catch (Exception e) {
                Log.warnf("[CacheSync] Failed to notify external cache=%s : %s",
                        externalCache, e.getMessage());
            }
        }

        return result;
    }

    private CacheInvalidate resolveAnnotation(final InvocationContext ctx) {
        final Method method = ctx.getMethod();
        final CacheInvalidate fromMethod = method.getAnnotation(CacheInvalidate.class);
        if (fromMethod != null) {
            return fromMethod;
        }
        return method.getDeclaringClass().getAnnotation(CacheInvalidate.class);
    }
}
