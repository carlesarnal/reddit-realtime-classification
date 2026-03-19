package io.apicurio;

import com.fasterxml.jackson.databind.JsonNode;
import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import io.apicurio.registry.resolver.strategy.ArtifactReferenceImpl;
import io.apicurio.schema.validation.json.JsonMetadata;
import io.apicurio.schema.validation.json.JsonRecord;
import io.apicurio.schema.validation.json.JsonValidationResult;
import io.apicurio.schema.validation.json.JsonValidator;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Path("/models")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ModelController {

    final JsonValidator validator = new JsonValidator(Map.of(SchemaResolverConfig.REGISTRY_URL,
        System.getenv().getOrDefault("APICURIO_REGISTRY_URL", "http://apicurio-registry.reddit-realtime.svc:8080") + "/apis/registry/v3"), Optional.empty());

    private final Map<String, JsonNode> store = new ConcurrentHashMap<>();

    @POST
    public Response registerModel(JsonNode newModel) {
        try {
            JsonMetadata jsonMetadata = new JsonMetadata(new ArtifactReferenceImpl.ArtifactReferenceBuilder()
                    .groupId("mcp-models")
                    .artifactId("model-context-schema")
                    .build());

            JsonRecord record = new JsonRecord(newModel, jsonMetadata);

            JsonValidationResult validationResult = validator.validate(record);

            if (validationResult.success()) {
                // Model is valid
                String modelId = newModel.get("name").asText();
                store.put(modelId, newModel);
                return Response.status(Response.Status.CREATED)
                        .entity(Map.of("modelId", modelId))
                        .build();
            }
            else {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Model validation failed", "details", validationResult.getValidationErrors()))
                        .build();
            }
        }
        catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    public Collection<JsonNode> listModels() {
        return store.values();
    }

    @GET
    @Path("/{id}")
    public Response getModel(@PathParam("id") String id) {
        JsonNode model = store.get(id);
        if (model == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(model).build();
    }
}

