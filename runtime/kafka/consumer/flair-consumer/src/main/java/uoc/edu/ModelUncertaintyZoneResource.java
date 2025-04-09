package uoc.edu;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@Path("/flairs")
@ApplicationScoped
public class ModelUncertaintyZoneResource extends BaseResource {

    @GET
    @Path("/uncertainty-zones")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUncertaintyStats() {
        JsonObject response = new JsonObject()
                .put("both_confident", bothConfident)
                .put("both_uncertain", bothUncertain)
                .put("disagreement", disagreement);
        return Response.ok(response).build();
    }
}