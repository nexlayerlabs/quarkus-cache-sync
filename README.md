# quarkus-cache-sync

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nexlayerlabs/quarkus-cache-sync?color=blue&logo=apache-maven)](https://central.sonatype.com/artifact/io.github.nexlayerlabs/quarkus-cache-sync)
[![GitHub Actions](https://github.com/nexlayerlabs/quarkus-cache-sync/actions/workflows/ci.yml/badge.svg)](https://github.com/nexlayerlabs/quarkus-cache-sync/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.8.0-blueviolet?logo=quarkus)](https://quarkus.io)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)

Production-ready **distributed cache orchestration** for Quarkus microservices running on Kubernetes and OpenShift.

Designed for teams running **multiple services with multiple pods each** — where existing cache libraries leave you managing cross-pod invalidation, cache stampedes, and cold-start spikes manually.

---

## Why this library?

Standard cache solutions like Spring `@Cacheable` or Quarkus `@CacheResult` handle basic caching well but break down in multi-pod environments:

| Problem | Without `quarkus-cache-sync` | With `quarkus-cache-sync` |
|---|---|---|
| Cross-pod invalidation | Pod 1 invalidates, Pods 2–4 serve stale data | All pods invalidate via Redis Pub/Sub |
| Cache stampede | All 4 pods hit DB simultaneously on key expiry | Distributed lock — only 1 pod reloads |
| Cold start spike | Every new pod hits DB on startup | One pod warms Redis; all pods share the result |
| Boilerplate | Manual Redis client, key management, error handling | `@Cached` annotation — one line |
| Metrics | Custom setup per service | Built-in Micrometer counters out-of-the-box |

---

## Features

- **`@Cached`** — Cache method results in Redis with TTL, custom keys, and conditions
- **`@CacheInvalidate`** — Evict named caches after writes; broadcast to all pods
- **`@CacheWarmup`** — ShedLock-backed pod-aware warmup; exactly one pod warms on startup
- **Cross-pod sync** — Redis Pub/Sub ensures all pods invalidate together
- **Stampede prevention** — Distributed lock stops thundering herd on key expiry
- **Graceful fallback** — Redis failure silently falls back to direct DB calls
- **Micrometer metrics** — Hit/miss/error counters at `/q/metrics` out of the box
- **Zero boilerplate** — Auto-configured via Quarkus extension; 2-line setup per service

---

## Installation

Add to your service `pom.xml`:

```xml
<dependency>
    <groupId>io.github.nexlayerlabs</groupId>
    <artifactId>quarkus-cache-sync</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Quick Start

### 1. Deploy Redis (one-time, shared by all services)

```bash
oc apply -f https://raw.githubusercontent.com/nexlayerlabs/quarkus-cache-sync/main/redis-deployment.yaml

# Verify
oc get pods -l app=redis
oc exec -it redis-0 -- redis-cli ping   # PONG
```

### 2. Configure each service

```properties
# application.properties
quarkus.cache.sync.host=redis-service
quarkus.cache.sync.port=6379
quarkus.cache.sync.default-ttl=300
quarkus.cache.sync.enable-metrics=true
quarkus.cache.sync.fallback-enabled=true
quarkus.cache.sync.warmup.enabled=true
```

### 3. Annotate your service

```java
@ApplicationScoped
public class UserService {

    @Inject
    UserRepository repo;

    // Cache result for 5 minutes
    @Cached(ttl = 300)
    public User getUser(final String id) {
        return repo.findById(id).orElseThrow();
    }

    // Cache with stampede prevention (safe for high-traffic keys)
    @Cached(ttl = 600, preventStampede = true)
    public List<Product> getPopularProducts() {
        return repo.findPopular();
    }

    // Invalidate cache across ALL pods after update
    @CacheInvalidate(names = {"getUser"}, broadcastToPods = true)
    public User updateUser(final String id, final UserUpdate update) {
        return repo.save(id, update);
    }
}
```

---

## Annotations

### `@Cached`

```java
@Cached(
    ttl             = 300,                    // TTL in seconds (default: 300)
    unit            = TimeUnit.SECONDS,       // TimeUnit for ttl
    cacheName       = "users",                // Redis key prefix (default: method name)
    key             = "#{#userId}:profile",   // Custom SpEL key expression
    condition       = "#id != null",          // Condition to enable caching
    preventStampede = true,                   // Distributed lock on key expiry
    fallback        = true,                   // Fallback to DB if Redis fails
    recordMetrics   = true,                   // Emit Micrometer counters
    type            = CacheType.DISTRIBUTED   // DISTRIBUTED (Redis) or LOCAL
)
```

### `@CacheInvalidate`

```java
@CacheInvalidate(
    names           = {"users", "user-orders"},            // Caches to evict
    broadcastToPods = true,                                // Pub/Sub event to all pods
    externalCaches  = {"notification-service:user-prefs"}  // Notify other services
)
```

### `@CacheWarmup`

```java
@CacheWarmup(
    lockName     = "warmup-products",  // Unique ShedLock key across the cluster
    lockAtMost   = "PT5M",             // Max lock hold time (ISO-8601 duration)
    lockAtLeast  = "PT1M",             // Min lock hold time (prevents re-runs)
    runOnStartup = true                // Also run at application startup
)
```

---

## Real-World Example

```java
@ApplicationScoped
public class OrderService {

    @Inject OrderRepository orderRepo;
    @Inject RedisCacheManager cache;
    @Inject ProductRepository productRepo;

    @Cached(ttl = 600)
    public Order getOrder(final String id) {
        return orderRepo.findById(id).orElseThrow();
    }

    @Cached(ttl = 300, key = "#{#userId}:orders:#{#status}")
    public List<Order> getOrdersByStatus(final String userId, final String status) {
        return orderRepo.findByUserAndStatus(userId, status);
    }

    @CacheInvalidate(
        names = {"getOrder", "getOrdersByStatus"},
        broadcastToPods = true
    )
    public Order updateOrderStatus(final String id, final String status) {
        Order order = orderRepo.findById(id).orElseThrow();
        order.setStatus(status);
        return orderRepo.save(order);
    }

    // Runs on exactly ONE pod at startup. All pods share the result.
    @CacheWarmup(lockName = "warmup-popular-products", lockAtMost = "PT5M")
    @Scheduled(every = "1h")
    public void warmPopularProducts() {
        productRepo.findTop100Popular().forEach(p ->
            cache.preload("getProduct:" + p.getId(), p, 3600));
    }
}
```

---

## Cross-Pod Invalidation

```
Pod 1: updateUser("123") called
  → DB update
  → Deletes Redis key "getUser:123"
  → Publishes "cache-sync:invalidation" Pub/Sub event
         ↓            ↓            ↓
      Pod 2        Pod 3        Pod 4
   deletes key  deletes key  deletes key
         ↓            ↓            ↓
   All pods serve fresh data on next read ✅
```

## Pod-Aware Cache Warmup

```
Rolling deployment — 4 pods restarting sequentially

Pod 1 → acquires ShedLock → runs warmup → pre-loads 100 keys into Redis
Pod 2 → lock held         → skips warmup → reads warm cache ✅
Pod 3 → lock held         → skips warmup → reads warm cache ✅
Pod 4 → lock held         → skips warmup → reads warm cache ✅

Result: 1 DB query instead of 4. No cold-start spike. ✅
```

---

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `quarkus.cache.sync.host` | `localhost` | Redis hostname or Kubernetes service name |
| `quarkus.cache.sync.port` | `6379` | Redis port |
| `quarkus.cache.sync.password` | _(none)_ | Redis password (optional) |
| `quarkus.cache.sync.db` | `0` | Redis logical database (0–15) |
| `quarkus.cache.sync.timeout` | `2000` | Connection timeout in milliseconds |
| `quarkus.cache.sync.default-ttl` | `300` | Default TTL in seconds |
| `quarkus.cache.sync.enable-metrics` | `true` | Emit Micrometer counters |
| `quarkus.cache.sync.fallback-enabled` | `true` | Fallback to DB if Redis is down |
| `quarkus.cache.sync.warmup.enabled` | `true` | Enable ShedLock-backed warmup |
| `quarkus.cache.sync.warmup.lock-prefix` | `shedlock:` | Redis key prefix for ShedLock |

---

## Metrics

Exposed at `/q/metrics` via Micrometer:

```
cache_sync_hits_total{cache="getUser"}    # Cache hits
cache_sync_misses_total{cache="getUser"}  # Cache misses
cache_sync_errors_total{cache="getUser"}  # Redis errors
```

> **Tip:** A hit rate above **80%** means your TTL strategy is working well.
> `hit_rate = hits / (hits + misses)`

---

## Running Tests Locally

Tests use **Quarkus DevServices** — Redis starts automatically via Docker. No manual Redis setup needed.

**Prerequisite:** Docker must be running locally.

```bash
# Run all tests
mvn verify

# Run integration tests only
mvn verify -pl integration-tests

# Build without tests
mvn clean install -DskipTests
```

---

## Project Structure

```
quarkus-cache-sync/
├── runtime/                          ← Your Maven dependency
│   └── src/main/java/.../cache/
│       ├── Cached.java               ← @Cached annotation
│       ├── CacheInvalidate.java      ← @CacheInvalidate annotation
│       ├── CacheWarmup.java          ← @CacheWarmup annotation
│       ├── CacheConfig.java          ← application.properties mapping
│       ├── interceptor/
│       │   ├── CachedInterceptor.java
│       │   └── CacheInvalidationInterceptor.java
│       ├── redis/
│       │   ├── RedisCacheManager.java
│       │   └── PodInvalidationSubscriber.java
│       ├── metrics/
│       │   └── CacheMetricsCollector.java
│       └── warmup/
│           └── CacheWarmupExecutor.java
│
├── deployment/                       ← Quarkus build-time processor (internal)
│   └── src/main/java/.../deployment/
│       └── RedisCacheProcessor.java
│
├── integration-tests/                ← Local tests using DevServices Redis
│   └── src/
│       ├── main/java/...test/
│       │   ├── TestProductService.java
│       │   └── TestProductResource.java
│       └── test/java/...test/
│           └── CacheIT.java
│
├── redis-deployment.yaml             ← OpenShift / Kubernetes Redis StatefulSet
└── pom.xml                           ← Parent POM


## FAQ

**Do I need to add Redis to each service?**
No. Deploy one shared Redis StatefulSet in your cluster. All services point to the same `redis-service` hostname via two config lines.

**What if Redis goes down?**
With `fallback-enabled=true` (default), all cache operations silently degrade — services keep calling the database normally with no exceptions thrown.

**Does this replace Caffeine or Ehcache?**
No. This library handles the **distributed, multi-pod coordination layer**. Caffeine is a local in-process cache. They solve different problems.

**Is a ShedLock table required?**
No table needed. This library uses `shedlock-provider-redis-quarkus2`, which stores locks directly as Redis keys — no database table required.

---

## Contributing

Pull requests are welcome. Please open an issue first to discuss what you would like to change.

```bash
git clone https://github.com/nexlayerlabs/quarkus-cache-sync.git
cd quarkus-cache-sync
mvn clean install
```

---

## License

[Apache License 2.0](LICENSE)
