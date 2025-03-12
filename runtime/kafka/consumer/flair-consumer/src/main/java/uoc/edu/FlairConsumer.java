package uoc.edu;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
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

        return Uni.createFrom().nullItem();
    }

    private String extractFlair(String json) {
        try {
            return json.split("\"flair\":\"")[1].split("\"")[0];
        }
        catch (Exception e) {
            LOG.error("Error parsing flair", e);
            return null;
        }
    }

    // REST endpoint for retrieving flair statistics
    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/flairs")
    @jakarta.ws.rs.Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    public Response getFlairStatistics() {
        return Response.ok(flairCount).build();
    }
}

