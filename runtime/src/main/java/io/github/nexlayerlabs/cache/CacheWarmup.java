package io.github.nexlayerlabs.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
<parameter name="file_text">package io.github.nexlayerlabs.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a pod-aware cache warmup routine.
 *
 * <h2>Why this is needed</h2>
 * <p>When a new pod starts (e.g. during autoscaling or a rolling deployment),
 * its local state is cold. Without warmup, the first N requests hit the database
 * causing a traffic spike. In a 4-pod cluster doing a rolling deployment, all 4
 * pods restart sequentially — each one spikes the DB.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Each pod calls the annotated method on startup (via {@code @Scheduled}).</li>
 *   <li>ShedLock acquires a distributed lock in Redis before execution.</li>
 *   <li>Only <b>one pod</b> runs the warmup; the rest skip it.</li>
 *   <li>That one pod pre-loads critical keys into Redis.</li>
 *   <li>All pods then read from Redis (warm cache) instead of the DB.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @CacheWarmup(
 *     lockName    = "warmup-popular-products",
 *     lockAtMost  = "PT5M",
 *     lockAtLeast = "PT1M"
 * )
 * @Scheduled(every = "1h")
 * public void warmPopularProducts() {
 *     List<Product> products = productRepository.findPopular();
 *     products.forEach(p -> cache.preload("product:" + p.getId(), p, 3600));
 * }
 * }</pre>
 *
 * <p><b>Requires ShedLock with Redis provider.</b>
 * Ensure {@code quarkus.shedlock.enabled=true} in your {@code application.properties}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CacheWarmup {

    /**
     * Unique ShedLock lock name across the cluster.
     * Must be unique per warmup task to prevent cross-task interference.
     */
    String lockName();

    /**
     * ISO-8601 duration: maximum time the lock is held.
     * If the pod holding the lock crashes, the lock auto-expires after this.
     * Example: {@code "PT5M"} = 5 minutes.
     */
    String lockAtMost() default "PT5M";

    /**
     * ISO-8601 duration: minimum time the lock is held, even if warmup finishes early.
     * Prevents another pod from re-running warmup immediately after the first one.
     * Example: {@code "PT1M"} = 1 minute.
     */
    String lockAtLeast() default "PT1M";

    /**
     * When {@code true}, also runs warmup during application startup
     * in addition to the scheduled interval.
     */
    boolean runOnStartup() default true;
}
