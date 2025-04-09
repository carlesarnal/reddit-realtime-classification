package uoc.edu;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Path("/flairs")
@ApplicationScoped
public class TimelineResource {

    private final Map<String, Map<String, Integer>> timelineCounts = new ConcurrentHashMap<>();


    @GET
    @Path("/timeline")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTimeline() {
        JsonObject response = new JsonObject();

        for (Map.Entry<String, Map<String, Integer>> entry : timelineCounts.entrySet()) {
            String bucket = entry.getKey();
            JsonObject flairCounts = new JsonObject();

            for (Map.Entry<String, Integer> flairEntry : entry.getValue().entrySet()) {
                flairCounts.put(flairEntry.getKey(), flairEntry.getValue());
            }

            response.put(bucket, flairCounts);
        }

        return Response.ok(response).build();
    }
}
