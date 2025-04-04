package uoc.edu;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
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

        try {
            JsonObject json = new JsonObject(record.getPayload());

            // Get flair predictions and confidences
            String transformerFlair = json.getString("transformer_flair", "Unknown");
            double transformerConf = json.getDouble("transformer_confidence", 0.0);

            String sklearnFlair = json.getString("sklearn_flair", "Unknown");
            double sklearnConf = json.getDouble("sklearn_confidence", 0.0);

            boolean agrees = transformerFlair.equals(sklearnFlair);
            double gap = Math.abs(transformerConf - sklearnConf);

            // Update counts and confidences using transformer prediction
            flairCount.merge(transformerFlair, 1, Integer::sum);
            flairConfidenceSum.merge(transformerFlair, transformerConf, Double::sum);

            // Metrics
            registry.counter("flair_messages_total", "flair", transformerFlair).increment();
            registry.counter("flair_comparisons_total", "agrees", String.valueOf(agrees)).increment();
            registry.counter("flair_message_source_total", "model", "transformer").increment();
            registry.counter("flair_message_source_total", "model", "sklearn").increment();

            registry.summary("flair_confidence_gap", "flair", transformerFlair).record(gap);

            record.ack();
        } catch (Exception e) {
            registry.counter("flair_message_errors_total").increment();
            LOG.error("Failed to parse message", e);
        }

        return Uni.createFrom().nullItem();
    }

    @GET
    @Path("/statistics")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFlairStatistics() {
        JsonObject stats = new JsonObject();
        for (Map.Entry<String, Integer> entry : flairCount.entrySet()) {
            String flair = entry.getKey();
            int count = entry.getValue();
            double avgConfidence = flairConfidenceSum.getOrDefault(flair, 0.0) / count;
            stats.put(flair, new JsonObject()
                    .put("count", count)
                    .put("avg_confidence", Math.round(avgConfidence * 100.0) / 100.0));
        }
        return Response.ok(stats).build();
    }
}
