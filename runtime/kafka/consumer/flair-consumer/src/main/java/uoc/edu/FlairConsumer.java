package uoc.edu;

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
    private final Map<String, Integer> flairAgreementCount = new ConcurrentHashMap<>();
    private final Map<String, Double> transformerConfidenceSum = new ConcurrentHashMap<>();
    private final Map<String, Double> sklearnConfidenceSum = new ConcurrentHashMap<>();
    private final Map<String, Integer> comparisonCount = new ConcurrentHashMap<>();
    private final Map<String, Double> confidenceGapSum = new ConcurrentHashMap<>();

    @Inject
    MeterRegistry registry;

    @Incoming("kafka-predictions")
    @Blocking
    public Uni<Void> consume(KafkaRecord<String, String> record) {
        LOG.infof("Received message: %s", record.getPayload());

        try {
            JsonObject json = new JsonObject(record.getPayload());
            String id = json.getString("id");
            String flair = json.getString("predicted_flair", "Unknown");
            Double transformerConfidence = json.getDouble("confidence", 0.0);
            String flairSklearn = json.getString("predicted_flair_sklearn", flair);
            Double sklearnConfidence = json.getDouble("confidence_sklearn", transformerConfidence);

            // Update core counts
            flairCount.merge(flair, 1, Integer::sum);
            flairConfidenceSum.merge(flair, transformerConfidence, Double::sum);

            // Compare predictions
            comparisonCount.merge(flair, 1, Integer::sum);
            if (flair.equals(flairSklearn)) {
                flairAgreementCount.merge(flair, 1, Integer::sum);
            }

            // Track confidence deltas
            transformerConfidenceSum.merge(flair, transformerConfidence, Double::sum);
            sklearnConfidenceSum.merge(flair, sklearnConfidence, Double::sum);
            confidenceGapSum.merge(flair, Math.abs(transformerConfidence - sklearnConfidence), Double::sum);

            // Metric: increment per-model agreement counter
            registry.counter("flair_comparisons_total", "flair", flair).increment();
            if (flair.equals(flairSklearn)) {
                registry.counter("flair_agreements_total", "flair", flair).increment();
            }
            else {
                registry.counter("flair_disagreements_total", "flair", flair).increment();
            }

            registry.counter("flair_messages_total", "flair", flair).increment();
            record.ack();

        }
        catch (Exception e) {
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
            double avgTransformer = transformerConfidenceSum.getOrDefault(flair, 0.0) / count;
            double avgSklearn = sklearnConfidenceSum.getOrDefault(flair, 0.0) / count;
            double agreementRate = flairAgreementCount.getOrDefault(flair, 0) / (double) comparisonCount.getOrDefault(flair, 1);
            double avgConfidenceGap = confidenceGapSum.getOrDefault(flair, 0.0) / comparisonCount.getOrDefault(flair, 1);

            stats.put(flair, new JsonObject()
                    .put("count", count)
                    .put("avg_confidence", round(avgConfidence))
                    .put("avg_confidence_transformer", round(avgTransformer))
                    .put("avg_confidence_sklearn", round(avgSklearn))
                    .put("agreement_rate", round(agreementRate))
                    .put("avg_confidence_gap", round(avgConfidenceGap))
            );
        }

        JsonObject summary = new JsonObject();
        summary.put("flairs", stats);
        summary.put("total_comparisons", comparisonCount.values().stream().mapToInt(i -> i).sum());

        return Response.ok(summary).build();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
