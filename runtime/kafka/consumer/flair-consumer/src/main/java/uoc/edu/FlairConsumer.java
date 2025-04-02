package uoc.edu;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@Path("/flairs")
public class FlairConsumer {

    private static final Logger LOG = Logger.getLogger(FlairConsumer.class);

    private final Map<String, Integer> flairCount = new ConcurrentHashMap<>();
    private final Map<String, Double> flairConfidenceSum = new ConcurrentHashMap<>();

    @Inject
    MeterRegistry registry;

    @Incoming("kafka-predictions")
    @Blocking
    public Uni<Void> consume(KafkaRecord<String, String> record) {
        LOG.infof("Received message: %s", record.getPayload());

        // Parse message
        try {
            JsonObject json = new JsonObject(record.getPayload());
            String flair = json.getString("predicted_flair", "Unknown");
            Double confidence = json.getDouble("confidence", 0.0);

            flairCount.merge(flair, 1, Integer::sum);
            flairConfidenceSum.merge(flair, confidence, Double::sum);

            record.ack();

            Counter flairCounter = registry.counter("flair_messages_total", "flair", flair);
            flairCounter.increment();

        }
        catch (Exception e) {
            Counter errorCounter = registry.counter("flair_message_errors_total");
            errorCounter.increment();
            LOG.error("Failed to parse message", e);

        }
        return Uni.createFrom().nullItem();
    }

    // REST endpoint for retrieving flair statistics
    @GET
    @Path("/statistics")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFlairStatistics() {
        JsonObject stats = new JsonObject();
        for (Map.Entry<String, Integer> entry : flairCount.entrySet()) {
            String flair = entry.getKey();
            int count = entry.getValue();
            double avgConfidence = flairConfidenceSum.getOrDefault(flair, 0.0) / count;
            stats.put(flair, new JsonObject().put("count", count).put("avg_confidence", Math.round(avgConfidence * 100.0) / 100.0));
        }
        return Response.ok(stats).build();
    }
}