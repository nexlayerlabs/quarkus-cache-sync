package io.github.nexlayerlabs.cache.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects cache hit, miss, and error counts.
 *
 * <p>Metrics are exposed via Micrometer at {@code /q/metrics} (Prometheus scrape).
 * The following counters are published:
 * <ul>
 *   <li>{@code cache_sync_hits_total{cache="..."}} </li>
 *   <li>{@code cache_sync_misses_total{cache="..."}} </li>
 *   <li>{@code cache_sync_errors_total{cache="..."}} </li>
 * </ul>
 *
 * <p>In-memory totals are also kept in {@link LongAdder} maps for
 * programmatic access via {@link #getSummary()}.
 */
@ApplicationScoped
public class CacheMetricsCollector {

    private static final String METRIC_HITS   = "cache_sync_hits_total";
    private static final String METRIC_MISSES = "cache_sync_misses_total";
    private static final String METRIC_ERRORS = "cache_sync_errors_total";
    private static final String TAG_CACHE     = "cache";

    @Inject
    MeterRegistry registry;

    private final Map<String, LongAdder> hits   = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> misses  = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errors  = new ConcurrentHashMap<>();

    public void recordHit(final String cacheName) {
        hits.computeIfAbsent(cacheName, k -> new LongAdder()).increment();
        counter(METRIC_HITS, cacheName).increment();
    }

    public void recordMiss(final String cacheName) {
        misses.computeIfAbsent(cacheName, k -> new LongAdder()).increment();
        counter(METRIC_MISSES, cacheName).increment();
    }

    public void recordError(final String cacheName) {
        errors.computeIfAbsent(cacheName, k -> new LongAdder()).increment();
        counter(METRIC_ERRORS, cacheName).increment();
    }

    /**
     * Returns the hit rate for the given cache name (between 0.0 and 1.0).
     * Returns 0.0 if no requests have been recorded yet.
     */
    public double hitRate(final String cacheName) {
        final long h = count(hits, cacheName);
        final long m = count(misses, cacheName);
        final long total = h + m;
        return total == 0 ? 0.0 : (double) h / total;
    }

    /**
     * Returns a summary map suitable for a health or info endpoint.
     */
    public Map<String, Object> getSummary() {
        final long totalHits   = hits.values().stream().mapToLong(LongAdder::sum).sum();
        final long totalMisses = misses.values().stream().mapToLong(LongAdder::sum).sum();
        final long totalErrors = errors.values().stream().mapToLong(LongAdder::sum).sum();
        final long total = totalHits + totalMisses;

        return Map.of(
                "totalHits",   totalHits,
                "totalMisses", totalMisses,
                "totalErrors", totalErrors,
                "overallHitRate", total == 0 ? 0.0 : (double) totalHits / total
        );
    }

    // -------------------------------------------------------------------------

    private Counter counter(final String metric, final String cacheName) {
        return Counter.builder(metric)
                .tags(List.of(Tag.of(TAG_CACHE, cacheName)))
                .register(registry);
    }

    private long count(final Map<String, LongAdder> map, final String key) {
        final LongAdder adder = map.get(key);
        return adder == null ? 0L : adder.sum();
    }
}
