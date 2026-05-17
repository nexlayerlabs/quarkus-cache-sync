package io.github.nexlayerlabs.cache.test;

import io.github.nexlayerlabs.cache.Cached;
import io.github.nexlayerlabs.cache.CacheInvalidate;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake in-memory service that simulates a real service (e.g. UserService,
 * ProductService). Used ONLY in integration tests.
 *
 * <p>The {@link #callCount} lets tests verify whether the method body was
 * actually executed or the result came from the cache.
 */
@ApplicationScoped
public class TestProductService {

    private final AtomicInteger callCount = new AtomicInteger(0);

    @Cached(ttl = 60)
    public Product getProduct(final String id) {
        callCount.incrementAndGet();
        return new Product(id, "Product-" + id, 9.99);
    }

    @Cached(ttl = 60, preventStampede = true)
    public Product getProductSafe(final String id) {
        callCount.incrementAndGet();
        return new Product(id, "SafeProduct-" + id, 19.99);
    }

    @CacheInvalidate(names = {"getProduct", "getProductSafe"}, broadcastToPods = true)
    public Product updateProduct(final String id, final String newName) {
        return new Product(id, newName, 0.0);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }

    // -------------------------------------------------------------------------

    public record Product(String id, String name, double price)
            implements Serializable {
    }
}
