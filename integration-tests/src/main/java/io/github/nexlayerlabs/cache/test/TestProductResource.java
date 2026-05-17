package io.github.nexlayerlabs.cache.test;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST endpoints used exclusively by integration tests.
 * This resource is inside {@code integration-tests/src/main} (not test sources)
 * so Quarkus can boot it as a real application during {@code mvn verify}.
 */
@Path("/test")
@Produces(MediaType.APPLICATION_JSON)
public class TestProductResource {

    @Inject
    TestProductService service;

    @GET
    @Path("/products/{id}")
    public Response getProduct(@PathParam("id") final String id) {
        return Response.ok(service.getProduct(id)).build();
    }

    @GET
    @Path("/products/{id}/safe")
    public Response getProductSafe(@PathParam("id") final String id) {
        return Response.ok(service.getProductSafe(id)).build();
    }

    @PUT
    @Path("/products/{id}")
    public Response updateProduct(@PathParam("id") final String id,
                                   final String newName) {
        return Response.ok(service.updateProduct(id, newName)).build();
    }

    @GET
    @Path("/call-count")
    public Response callCount() {
        return Response.ok(Map.of("count", service.getCallCount())).build();
    }

    @GET
    @Path("/reset")
    public Response reset() {
        service.resetCallCount();
        return Response.ok(Map.of("reset", true)).build();
    }
}
