package io.github.nexlayerlabs.cache.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nexlayerlabs.cache.Cached;
import io.github.nexlayerlabs.cache.CacheConfig;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Central Redis operations facade for the cache-sync library.
 *
 * <p>All cache reads, writes, key deletions, and Pub/Sub publications
 * go through this single class. Keeps the interceptors thin and testable.
 */
@ApplicationScoped
public class RedisCacheManager {

    private static final String INVALIDATION_CHANNEL = "cache-sync:invalidation";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    RedisDataSource redis;

    @Inject
    CacheConfig config;

    private ValueCommands<String, String> valueCommands;
    private KeyCommands<String> keyCommands;
    private PubSubCommands<String> pubSubCommands;

    @PostConstruct
    void init() {
        valueCommands  = redis.value(String.class);
        keyCommands    = redis.key();
        pubSubCommands = redis.pubsub(String.class);
        Log.infof("[CacheSync] Connected to Redis at %s:%d", config.host(), config.port());
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    /**
     * Returns the cached value for {@code key}, or {@link Optional#empty()} on
     * a miss or deserialization failure.
     */
    public Optional<Object> get(final String key, final Cached.CacheType type) {
        if (type == Cached.CacheType.LOCAL) {
            return Optional.empty(); // local cache handled by Caffeine (future)
        }
        try {
            final String raw = valueCommands.get(key);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(raw));
        } catch (Exception e) {
            Log.warnf("[CacheSync] GET failed for key=%s : %s", key, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // WRITE
    // -------------------------------------------------------------------------

    /**
     * Stores {@code value} under {@code key} with an expiry of {@code ttlSeconds}.
     * Silently ignores failures so the application degrades gracefully.
     */
    public void set(final String key, final Object value, final long ttlSeconds,
                    final Cached.CacheType type) {
        if (type == Cached.CacheType.LOCAL) {
            return;
        }
        try {
            final String serialized = serialize(value);
            final SetArgs args = new SetArgs().ex(Duration.ofSeconds(ttlSeconds));
            valueCommands.set(key, serialized, args);
            Log.debugf("[CacheSync] SET key=%s ttl=%ds", key, ttlSeconds);
        } catch (Exception e) {
            Log.warnf("[CacheSync] SET failed for key=%s : %s", key, e.getMessage());
        }
    }

    /**
     * Pre-loads a value into the cache (used by {@link io.github.nexlayerlabs.cache.CacheWarmup}).
     * Same as {@link #set} but with a descriptive name for clarity in warmup code.
     */
    public void preload(final String key, final Object value, final long ttlSeconds) {
        set(key, value, ttlSeconds, Cached.CacheType.DISTRIBUTED);
        Log.infof("[CacheSync] PRELOAD key=%s ttl=%ds", key, ttlSeconds);
    }

    // -------------------------------------------------------------------------
    // INVALIDATION
    // -------------------------------------------------------------------------

    /**
     * Deletes all Redis keys whose name starts with {@code cacheName:}.
     */
    public void invalidate(final String cacheName) {
        try {
            final List<String> keys = keyCommands.keys(cacheName + ":*");
            if (!keys.isEmpty()) {
                keyCommands.del(keys.toArray(new String[0]));
                Log.infof("[CacheSync] INVALIDATED %d key(s) for cache=%s",
                        keys.size(), cacheName);
            }
        } catch (Exception e) {
            Log.warnf("[CacheSync] INVALIDATE failed for cache=%s : %s",
                    cacheName, e.getMessage());
        }
    }

    /**
     * Publishes a Redis Pub/Sub message so all other pods in the cluster
     * also invalidate their copies of {@code cacheName}.
     *
     * <p>The message format is: {@code cacheName:invalidated}
     */
    public void publishInvalidationEvent(final String cacheName) {
        try {
            final String message = cacheName + ":invalidated";
            pubSubCommands.publish(INVALIDATION_CHANNEL, message);
            Log.debugf("[CacheSync] PUBLISH invalidation event for cache=%s", cacheName);
        } catch (Exception e) {
            Log.warnf("[CacheSync] PUBLISH failed for cache=%s : %s",
                    cacheName, e.getMessage());
        }
    }

    /**
     * Notifies an external service to invalidate its own cache.
     * The format of {@code externalCache} is {@code "service-name:cache-name"}.
     * Implementations can extend this to send an HTTP event or a Kafka message.
     */
    public void notifyExternalService(final String externalCache) {
        // Placeholder: emit an HTTP call or Kafka event to the target service.
        Log.infof("[CacheSync] External invalidation requested for: %s", externalCache);
    }

    // -------------------------------------------------------------------------
    // CHANNEL NAME (used by pod-invalidation subscriber)
    // -------------------------------------------------------------------------

    public static String invalidationChannel() {
        return INVALIDATION_CHANNEL;
    }

    // -------------------------------------------------------------------------
    // SERIALIZATION
    // -------------------------------------------------------------------------

    private String serialize(final Object obj) throws JsonProcessingException {
        if (obj instanceof String s) {
            return s;
        }
        return MAPPER.writeValueAsString(obj);
    }

    private Object deserialize(final String json) throws JsonProcessingException {
        // Returns a generic Map/List/String depending on JSON shape.
        // Callers that need typed results should override with a typed variant.
        return MAPPER.readValue(json, Object.class);
    }
}
