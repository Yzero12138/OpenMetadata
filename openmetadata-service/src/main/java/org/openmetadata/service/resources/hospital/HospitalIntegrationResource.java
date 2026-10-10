package org.openmetadata.service.resources.hospital;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;
import org.openmetadata.service.Entity;
import org.openmetadata.service.integration.EntityExtensionTaskStore;
import org.openmetadata.service.integration.IntegrationConfiguration;
import org.openmetadata.service.integration.IntegrationException;
import org.openmetadata.service.integration.IntegrationModels;
import org.openmetadata.service.integration.IntegrationModels.ConnectionUpdate;
import org.openmetadata.service.integration.IntegrationModels.RunInput;
import org.openmetadata.service.integration.IntegrationModels.StopInput;
import org.openmetadata.service.integration.IntegrationModels.UpdateInput;
import org.openmetadata.service.integration.IntegrationService;
import org.openmetadata.service.resources.Collection;
import org.openmetadata.service.security.Authorizer;

@Path("/v1/hospital/integration")
@Collection(name = "hospitalIntegration", order = 9)
@Tag(
    name = "Hospital Integration",
    description = "Admin-only registered data sources and integration tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class HospitalIntegrationResource {
  private final Authorizer authorizer;
  private final IntegrationService service;

  public HospitalIntegrationResource(Authorizer authorizer) {
    this.authorizer = authorizer;
    service =
        new IntegrationService(
            IntegrationConfiguration.fromEnvironment(),
            new EntityExtensionTaskStore(() -> Entity.getCollectionDAO().entityExtensionDAO()));
  }

  @GET
  @Path("/status")
  public Response status(@Context SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(service.status()).build());
  }

  @GET
  @Path("/connections")
  public Response connections(@Context SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(Map.of("data", service.connections())).build());
  }

  @POST
  @Path("/connections")
  public Response createConnection(@Context SecurityContext securityContext, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () ->
            Response.status(Response.Status.CREATED)
                .entity(
                    service.createConnection(
                        IntegrationModels.readConnection(IntegrationModels.readBody(input), false),
                        actor(securityContext)))
                .build());
  }

  @GET
  @Path("/connections/{id}")
  public Response connection(@Context SecurityContext securityContext, @PathParam("id") String id) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(service.connection(id)).build());
  }

  @PUT
  @Path("/connections/{id}")
  public Response updateConnection(
      @Context SecurityContext securityContext, @PathParam("id") String id, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () ->
            Response.ok(
                    service.updateConnection(
                        id,
                        (ConnectionUpdate)
                            IntegrationModels.readConnection(
                                IntegrationModels.readBody(input), true),
                        actor(securityContext)))
                .build());
  }

  @DELETE
  @Path("/connections/{id}")
  public Response deleteConnection(
      @Context SecurityContext securityContext,
      @PathParam("id") String id,
      @QueryParam("version") long version) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () -> {
          service.deleteConnection(id, version);
          return Response.noContent().build();
        });
  }

  @POST
  @Path("/connections/{id}/test")
  public Response testConnection(
      @Context SecurityContext securityContext, @PathParam("id") String id) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(service.testConnection(id)).build());
  }

  @GET
  @Path("/connections/{id}/tables")
  public Response tables(@Context SecurityContext securityContext, @PathParam("id") String id) {
    return discoverTables(securityContext, id, true);
  }

  @GET
  @Path("/connections/{id}/table-options")
  public Response tableOptions(
      @Context SecurityContext securityContext, @PathParam("id") String id) {
    return discoverTables(securityContext, id, false);
  }

  private Response discoverTables(
      SecurityContext securityContext, String id, boolean includeColumns) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(Map.of("data", service.tables(id, includeColumns))).build());
  }

  @GET
  @Path("/connections/{id}/tables/{schema}/{table}")
  public Response table(
      @Context SecurityContext securityContext,
      @PathParam("id") String id,
      @PathParam("schema") String schema,
      @PathParam("table") String table) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(service.table(id, schema, table)).build());
  }

  @GET
  @Path("/tasks")
  public Response tasks(@Context SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () -> Response.ok(Map.of("data", service.tasks(actor(securityContext)))).build());
  }

  @POST
  @Path("/tasks/validate")
  public Response validate(@Context SecurityContext securityContext, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () ->
            Response.ok(
                    service.validate(
                        IntegrationModels.readInput(IntegrationModels.readBody(input), false)))
                .build());
  }

  @POST
  @Path("/tasks")
  public Response create(@Context SecurityContext securityContext, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () ->
            Response.status(Response.Status.CREATED)
                .entity(
                    service.create(
                        IntegrationModels.readInput(IntegrationModels.readBody(input), false),
                        actor(securityContext)))
                .build());
  }

  @GET
  @Path("/tasks/{id}")
  public Response task(@Context SecurityContext securityContext, @PathParam("id") String id) {
    authorizer.authorizeAdmin(securityContext);
    return respond(() -> Response.ok(service.task(id, actor(securityContext))).build());
  }

  @PUT
  @Path("/tasks/{id}")
  public Response update(
      @Context SecurityContext securityContext, @PathParam("id") String id, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () ->
            Response.ok(
                    service.update(
                        id,
                        (UpdateInput)
                            IntegrationModels.readInput(IntegrationModels.readBody(input), true),
                        actor(securityContext)))
                .build());
  }

  @POST
  @Path("/tasks/{id}/run")
  public Response run(
      @Context SecurityContext securityContext, @PathParam("id") String id, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () -> {
          JsonNode request = IntegrationModels.readBody(input);
          return Response.ok(
                  service.run(
                      id,
                      request != null && IntegrationModels.read(request, RunInput.class).resume(),
                      actor(securityContext)))
              .build();
        });
  }

  @POST
  @Path("/tasks/{id}/stop")
  public Response stop(
      @Context SecurityContext securityContext, @PathParam("id") String id, InputStream input) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () ->
            Response.ok(
                    service.stop(
                        id,
                        IntegrationModels.read(IntegrationModels.readBody(input), StopInput.class)
                            .savepoint(),
                        actor(securityContext)))
                .build());
  }

  @POST
  @Path("/tasks/{id}/catalog")
  public Response catalog(
      @Context SecurityContext securityContext,
      @Context UriInfo uriInfo,
      @PathParam("id") String id) {
    authorizer.authorizeAdmin(securityContext);
    return respond(
        () -> Response.ok(service.syncCatalog(id, uriInfo, actor(securityContext))).build());
  }

  private String actor(SecurityContext securityContext) {
    return securityContext.getUserPrincipal().getName();
  }

  private Response respond(Supplier<Response> operation) {
    try {
      return operation.get();
    } catch (IntegrationException e) {
      return Response.status(e.status())
          .entity(Map.of("errorCode", e.code(), "message", e.getMessage()))
          .build();
    } catch (RuntimeException e) {
      IntegrationException failure = new IntegrationException("STORAGE_UNAVAILABLE", 503);
      return Response.status(failure.status())
          .entity(Map.of("errorCode", failure.code(), "message", failure.getMessage()))
          .build();
    }
  }
}
