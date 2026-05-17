package io.github.nexlayerlabs.cache.warmup;

import io.github.nexlayerlabs.cache.CacheWarmup;
import io.github.nexlayerlabs.cache.CacheConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;

import java.time.Duration;
import java.time.Instant;

/**
 * Executes warmup tasks protected by a ShedLock distributed lock.
 *
 * <h2>Pod-aware warmup explained</h2>
 * <p>In a 4-pod deployment all pods start nearly simultaneously.
 * Without coordination, all 4 would run warmup at the same time — querying
 * the database 4 times for the same data and hammering Redis with duplicate writes.
 *
 * <p>This executor wraps each warmup call in a {@link LockingTaskExecutor}.
 * ShedLock stores a lock record in Redis (via {@link LockProvider}) with
 * {@code lockAtMost} and {@code lockAtLeast} bounds:
 *
 * <pre>
 *  Pod 1 → acquires lock  → runs warmup → releases after lockAtLeast
 *  Pod 2 → lock occupied  → skips warmup
 *  Pod 3 → lock occupied  → skips warmup
 *  Pod 4 → lock occupied  → skips warmup
 * </pre>
 *
 * <p>Result: exactly one DB query, all pods share the warmed Redis cache.
 */
@ApplicationScoped
public class CacheWarmupExecutor {

    @Inject
    LockProvider lockProvider;

    @Inject
    CacheConfig config;

    /**
     * Executes {@code task} under a ShedLock distributed lock derived from
     * the {@link CacheWarmup} annotation on the calling method.
     *
     * @param warmup  the annotation on the warmup method
     * @param task    the actual warmup logic to run
     */
    public void execute(final CacheWarmup warmup, final Runnable task) {
        if (!config.warmup().enabled()) {
            Log.debug("[CacheSync] Warmup disabled — skipping lock-protected execution");
            task.run();
            return;
        }

        final LockingTaskExecutor executor =
                new DefaultLockingTaskExecutor(lockProvider);

        final LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(),
                warmup.lockName(),
                Duration.parse(warmup.lockAtMost()),
                Duration.parse(warmup.lockAtLeast())
        );

        executor.executeWithLock(task, lockConfig);
        Log.infof("[CacheSync] Warmup executed under lock=%s", warmup.lockName());
    }
}
