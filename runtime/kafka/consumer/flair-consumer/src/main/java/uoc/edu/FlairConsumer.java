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

    private final Map<String, Integer> transformerCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> sklearnCounts = new ConcurrentHashMap<>();
    private final Map<String, Double> transformerConfidenceSum = new ConcurrentHashMap<>();
    private final Map<String, Double> sklearnConfidenceSum = new ConcurrentHashMap<>();

    private int totalCompared = 0;
    private int matchingPredictions = 0;

    @Inject
    MeterRegistry registry;

    @Incoming("kafka-predictions")
    @Blocking
    public Uni<Void> consume(KafkaRecord<String, String> record) {
        LOG.infof("Received message: %s", record.getPayload());

        try {
            JsonObject json = new JsonObject(record.getPayload());

            String transformerFlair = json.getString("transformer_flair", "Unknown");
            Double transformerConfidence = json.getDouble("transformer_confidence", 0.0);

            String sklearnFlair = json.getString("sklearn_flair", "Unknown");
            Double sklearnConfidence = json.getDouble("sklearn_confidence", 0.0);

            // Count and confidence updates
            transformerCounts.merge(transformerFlair, 1, Integer::sum);
            transformerConfidenceSum.merge(transformerFlair, transformerConfidence, Double::sum);

            sklearnCounts.merge(sklearnFlair, 1, Integer::sum);
            sklearnConfidenceSum.merge(sklearnFlair, sklearnConfidence, Double::sum);

            // Matching predictions counter
            totalCompared++;
            if (transformerFlair.equals(sklearnFlair)) {
                matchingPredictions++;
            }

            // Micrometer counters
            registry.counter("flair_transformer_total", "flair", transformerFlair).increment();
            registry.counter("flair_sklearn_total", "flair", sklearnFlair).increment();

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
public Response getStatistics() {
    JsonObject response = new JsonObject();
    JsonObject flairsJson = new JsonObject();

    for (String flair : transformerCounts.keySet()) {
        int transformerCount = transformerCounts.getOrDefault(flair, 0);
        int sklearnCount = sklearnCounts.getOrDefault(flair, 0);
        double transformerAvg = transformerConfidenceSum.getOrDefault(flair, 0.0) / Math.max(1, transformerCount);
        double sklearnAvg = sklearnConfidenceSum.getOrDefault(flair, 0.0) / Math.max(1, sklearnCount);

        double avgGap = Math.abs(transformerAvg - sklearnAvg);
        double agreementRate = (transformerCount > 0 && sklearnCount > 0 && transformerCount == sklearnCount) ? 1.0 : 0.0;

        JsonObject flairStats = new JsonObject()
            .put("count", Math.max(transformerCount, sklearnCount))
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