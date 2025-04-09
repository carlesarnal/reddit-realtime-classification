package uoc.edu;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/flairs")
@ApplicationScoped
public class AgreementTimelineResource extends BaseResource {

    @GET
    @Path("/agreement-timeline")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgreementTimeline() {
        JsonObject response = new JsonObject();
        for (Map.Entry<String, int[]> entry : agreementTimeline.entrySet()) {
            String bucket = entry.getKey();
            int[] stats = entry.getValue();
            double rate = stats[1] > 0 ? (double) stats[0] / stats[1] : 0.0;
            response.put(bucket, Math.round(rate * 1000.0) / 1000.0); // rounded to 3 decimals
        }
        return Response.ok(response).build();
    }
}
