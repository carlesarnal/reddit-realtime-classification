package uoc.edu;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@Path("/flairs")
public class FlairConsumer {

    private static final Logger LOG = Logger.getLogger(FlairConsumer.class);

    private final Map<String, Integer> flairCount = new ConcurrentHashMap<>();

    @Incoming("kafka-predictions")
    @Blocking
    public Uni<Void> consume(KafkaRecord<String, String> record) {
        LOG.infof("🔹 Received message: %s", record.getPayload());

        // Parse message
        String flair = extractFlair(record.getPayload());
        if (flair != null) {
            flairCount.merge(flair, 1, Integer::sum);
        }

        record.ack();

        return Uni.createFrom().nullItem();
    }


    private String extractFlair(String message) {
        try {
            JsonObject json = new JsonObject(message);
            return json.getString("predicted_flair", "Unknown"); // ✅ Safe extraction with a fallback
        } catch (Exception e) {
            System.err.println("❌ Error parsing JSON: " + e.getMessage());
            return "Unknown";
        }
    }


    // REST endpoint for retrieving flair statistics
    @GET
    @Path("/statistics")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFlairStatistics() {
        return Response.ok(flairCount).build();
    }
}

