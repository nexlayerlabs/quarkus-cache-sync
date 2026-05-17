# quarkus-cache-sync

Production-ready distributed cache orchestration for Quarkus microservices running on Kubernetes/OpenShift.

## Features

| Feature | Detail |
|---|---|
| `@Cached` | Cache method results in Redis with TTL |
| `@CacheInvalidate` | Evict named caches after writes |
| Cross-pod sync | Redis Pub/Sub — all pods invalidate together |
| Stampede prevention | Distributed lock prevents thundering herd |
| `@CacheWarmup` | ShedLock-backed pod-aware warmup on startup |
| Metrics | Micrometer counters for hits/misses/errors |
| Fallback | Graceful degradation if Redis is unavailable |
| Java 21 | Records, pattern matching, virtual threads ready |

## Installation

```xml
<dependency>
    <groupId>io.github.nexlayerlabs</groupId>
    <artifactId>quarkus-cache-sync</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick start

### 1. Add Redis config to `application.properties`

```properties
quarkus.cache.sync.host=redis-service
quarkus.cache.sync.port=6379
quarkus.cache.sync.default-ttl=300
quarkus.cache.sync.enable-metrics=true
quarkus.cache.sync.fallback-enabled=true
quarkus.cache.sync.warmup.enabled=true
```

### 2. Annotate your service

```java
@ApplicationScoped
public class UserService {

    @Inject UserRepository repo;

    @Cached(ttl = 300)
    public User getUser(final String id) {
        return repo.findById(id).orElseThrow();
    }

    @CacheInvalidate(names = "getUser", broadcastToPods = true)
    public User updateUser(final String id, final UserUpdate update) {
        return repo.save(id, update);
    }
}
```

### 3. (Optional) Pod-aware cache warmup

```java
@ApplicationScoped
public class WarmupScheduler {

    @Inject RedisCacheManager cache;
    @Inject ProductRepository repo;

    @CacheWarmup(lockName = "warmup-products", lockAtMost = "PT5M")
    @Scheduled(every = "1h")
    public void warmProducts() {
        // Runs on exactly ONE pod. All pods share the warmed cache.
        repo.findTop100Popular().forEach(p ->
            cache.preload("getProduct:" + p.getId(), p, 3600));
    }
}
```

## Configuration reference

| Property | Default | Description |
|---|---|---|
| `quarkus.cache.sync.host` | `localhost` | Redis hostname |
| `quarkus.cache.sync.port` | `6379` | Redis port |
| `quarkus.cache.sync.password` | _(none)_ | Redis password |
| `quarkus.cache.sync.db` | `0` | Redis DB index |
| `quarkus.cache.sync.timeout` | `2000` | Connection timeout (ms) |
| `quarkus.cache.sync.default-ttl` | `300` | Default TTL (seconds) |
| `quarkus.cache.sync.enable-metrics` | `true` | Micrometer metrics |
| `quarkus.cache.sync.fallback-enabled` | `true` | Skip cache on Redis failure |
| `quarkus.cache.sync.warmup.enabled` | `true` | ShedLock-backed warmup |
| `quarkus.cache.sync.warmup.lock-prefix` | `shedlock:` | Redis key prefix for locks |

## License

Apache 2.0
