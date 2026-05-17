package io.github.nexlayerlabs.cache.redis;

import io.github.nexlayerlabs.cache.CacheConfig;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Subscribes to the Redis Pub/Sub invalidation channel and forwards
 * incoming events to {@link RedisCacheManager#invalidate(String)}.
 *
 * <p>When any pod calls {@code @CacheInvalidate} with
 * {@code broadcastToPods = true}, it publishes a message like
 * {@code "user:invalidated"} to the {@code cache-sync:invalidation} channel.
 * Every other pod running this subscriber picks up the message and
 * invalidates its own cached keys — keeping all pods in sync.
 *
 * <p>The subscriber is started automatically on application startup
 * via {@link StartupEvent}.
 */
@ApplicationScoped
public class PodInvalidationSubscriber {

    @Inject
    RedisDataSource redis;

    @Inject
    RedisCacheManager cacheManager;

    @Inject
    CacheConfig config;

    /**
     * Starts the Redis Pub/Sub subscription when the application boots.
     * The handler runs asynchronously in a dedicated Redis connection.
     */
    void onStart(@Observes final StartupEvent event) {
        if (!config.warmup().enabled()) {
            Log.info("[CacheSync] Cross-pod invalidation is disabled via config.");
            return;
        }

        final PubSubCommands<String> subscriber = redis.pubsub(String.class);

        subscriber.subscribe(RedisCacheManager.invalidationChannel(), message -> {
            if (message != null && message.endsWith(":invalidated")) {
                final String cacheName = message.replace(":invalidated", "");
                Log.infof("[CacheSync] Received invalidation event for cache=%s — "
                        + "clearing local keys", cacheName);
                cacheManager.invalidate(cacheName);
            }
        });

        Log.infof("[CacheSync] Subscribed to Pub/Sub channel: %s",
                RedisCacheManager.invalidationChannel());
    }
}
