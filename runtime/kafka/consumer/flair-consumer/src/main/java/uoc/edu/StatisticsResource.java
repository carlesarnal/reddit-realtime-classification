package uoc.edu;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/flairs")
public class StatisticsResource extends BaseResource {

    @GET
    @Path("/statistics")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatistics() {
        JsonObject response = new JsonObject();
        JsonObject flairsJson = new JsonObject();

        for (String flair : transformerCounts.keySet()) {
            int transformerCount = transformerCounts.getOrDefault(flair, 0);
            int sklearnCount = sklearnCounts.getOrDefault(flair, 0);
            double transformerAvg = transformerConfidenceSum.getOrDefault(flair, 0.0) / Math.max(1, transformerCount);
            double sklearnAvg = sklearnConfidenceSum.getOrDefault(flair, 0.0) / Math.max(1, sklearnCount);
            double avgGap = Math.abs(transformerAvg - sklearnAvg);

            int agreements = flairAgreementCount.getOrDefault(flair, 0);
            int total = Math.max(transformerCount, sklearnCount);
            double agreementRate = total > 0 ? (double) agreements / total : 0.0;

            JsonObject flairStats = new JsonObject()
                    .put("count", total)
                    .put("avg_confidence", (transformerAvg + sklearnAvg) / 2)
                    .put("avg_confidence_transformer", transformerAvg)
                    .put("avg_confidence_sklearn", sklearnAvg)
                    .put("agreement_rate", agreementRate)
                    .put("avg_confidence_gap", Math.round(avgGap * 100.0) / 100.0);

            flairsJson.put(flair, flairStats);
        }

        response.put("flairs", flairsJson);
        return Response.ok(response).build();
    }
}