package uoc.edu;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/flairs")
@ApplicationScoped
public class ConfidenceDistributionResource extends BaseResource {

    @GET
    @Path("/confidence-distribution")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfidenceDistribution() {
        JsonObject response = new JsonObject();
        JsonArray labels = new JsonArray();
        JsonArray transformer = new JsonArray();
        JsonArray sklearn = new JsonArray();

        for (int i = 0; i < BUCKET_COUNT; i++) {
            double lower = i / (double) BUCKET_COUNT;
            double upper = (i + 1) / (double) BUCKET_COUNT;
            String label = String.format("%.1f–%.1f", lower, upper);
            labels.add(label);

            transformer.add(transformerBuckets[i]);
            sklearn.add(sklearnBuckets[i]);
        }

        response.put("labels", labels);
        response.put("transformer", transformer);
        response.put("sklearn", sklearn);

        return Response.ok(response).build();
    }
}
