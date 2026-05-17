package io.github.nexlayerlabs.cache.test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for the cache-sync extension.
 *
 * <h2>Local testing — no services needed</h2>
 * <p>These tests use Quarkus DevServices which automatically spins up a real
 * Redis container via Docker (Testcontainers under the hood).
 * You do NOT need to:
 * <ul>
 *   <li>Install Redis locally</li>
 *   <li>Point the tests at your real microservices</li>
 *   <li>Set up any environment variables</li>
 * </ul>
 * The only prerequisite is <b>Docker running on your machine</b>.
 *
 * <h2>Run</h2>
 * <pre>{@code
 * mvn verify -pl integration-tests
 * # or from project root:
 * mvn verify
 * }</pre>
 */
@QuarkusTest
class CacheIT {

    @BeforeEach
    void reset() {
        // Reset the in-memory call counter before each test
        given().get("/test/reset").then().statusCode(200);
    }

    @Test
    @DisplayName("First call hits the service; second call returns cached value")
    void testBasicCaching() {
        // First call — cache miss, service method executes
        given()
                .get("/test/products/p1")
                .then()
                .statusCode(200)
                .body("id", equalTo("p1"));

        assertCallCount(1);

        // Second call — cache hit, service method should NOT execute again
        given()
                .get("/test/products/p1")
                .then()
                .statusCode(200)
                .body("id", equalTo("p1"));

        assertCallCount(1); // still 1 — cache served this
    }

    @Test
    @DisplayName("Different keys are cached independently")
    void testDifferentKeysAreIndependent() {
        given().get("/test/products/x1").then().statusCode(200);
        given().get("/test/products/x2").then().statusCode(200);

        assertCallCount(2); // two different keys, two DB calls

        // Both should now be cached
        given().get("/test/products/x1").then().statusCode(200);
        given().get("/test/products/x2").then().statusCode(200);

        assertCallCount(2); // no additional calls
    }

    @Test
    @DisplayName("Cache invalidation causes next read to hit the service again")
    void testCacheInvalidation() {
        // Warm the cache
        given().get("/test/products/inv1").then().statusCode(200);
        assertCallCount(1);

        // Invalidate
        given()
                .body("UpdatedName")
                .contentType("application/json")
                .put("/test/products/inv1")
                .then()
                .statusCode(200);

        // Next read must go to the service again (cache was cleared)
        given().get("/test/products/inv1").then().statusCode(200);
        assertCallCount(2);
    }

    @Test
    @DisplayName("Stampede-protected method returns correct data")
    void testStampedeProtection() {
        given()
                .get("/test/products/s1/safe")
                .then()
                .statusCode(200)
                .body("id", equalTo("s1"));

        assertCallCount(1);

        // Should be served from cache
        given()
                .get("/test/products/s1/safe")
                .then()
                .statusCode(200)
                .body("id", equalTo("s1"));

        assertCallCount(1);
    }

    // -------------------------------------------------------------------------

    private void assertCallCount(final int expected) {
        given()
                .get("/test/call-count")
                .then()
                .statusCode(200)
                .body("count", equalTo(expected));
    }
}
