package io.github.nexlayerlabs.cache.deployment;

import io.github.nexlayerlabs.cache.Cached;
import io.github.nexlayerlabs.cache.CacheInvalidate;
import io.github.nexlayerlabs.cache.interceptor.CachedInterceptor;
import io.github.nexlayerlabs.cache.interceptor.CacheInvalidationInterceptor;
import io.github.nexlayerlabs.cache.metrics.CacheMetricsCollector;
import io.github.nexlayerlabs.cache.redis.RedisCacheManager;
import io.github.nexlayerlabs.cache.redis.PodInvalidationSubscriber;
import io.github.nexlayerlabs.cache.warmup.CacheWarmupExecutor;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.InterceptorBindingRegistrarBuildItem;
import io.quarkus.arc.processor.InterceptorBindingRegistrar;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus build-time processor for the cache-sync extension.
 *
 * <p>This class runs at <em>build time</em> (not runtime) to:
 * <ul>
 *   <li>Declare the {@code cache-sync} feature flag (visible in startup logs).</li>
 *   <li>Register all runtime CDI beans so Quarkus ArC includes them.</li>
 *   <li>Register {@link Cached} and {@link CacheInvalidate} as CDI interceptor
 *       bindings so ArC wires up the interceptors automatically.</li>
 * </ul>
 *
 * <p>End users never interact with this class directly.
 */
public class RedisCacheProcessor {

    private static final String FEATURE = "cache-sync";

    @BuildStep
    FeatureBuildItem declareFeature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(CachedInterceptor.class)
                .addBeanClass(CacheInvalidationInterceptor.class)
                .addBeanClass(RedisCacheManager.class)
                .addBeanClass(PodInvalidationSubscriber.class)
                .addBeanClass(CacheMetricsCollector.class)
                .addBeanClass(CacheWarmupExecutor.class)
                .setUnremovable()
                .build();
    }

    @BuildStep
    InterceptorBindingRegistrarBuildItem registerInterceptorBindings() {
        return new InterceptorBindingRegistrarBuildItem(
                (InterceptorBindingRegistrar.RegistrationContext context) -> {
                    context.registerInterceptorBinding(Cached.class);
                    context.registerInterceptorBinding(CacheInvalidate.class);
                }
        );
    }
}
