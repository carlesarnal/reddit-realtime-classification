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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@Path("/flairs")
public class FlairConsumer {

    private static final Logger LOG = Logger.getLogger(FlairConsumer.class);

    private final Map<String, Integer> transformerCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> sklearnCounts = new ConcurrentHashMap<>();
    private final Map<String, Double> transformerConfidenceSum = new ConcurrentHashMap<>();
    private final Map<String, Double> sklearnConfidenceSum = new ConcurrentHashMap<>();
    private final Map<String, Integer> flairAgreementCount = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> confusionMatrix = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> timelineCounts = new ConcurrentHashMap<>();

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("UTC")); // Or your preferred time zone

    @Inject
    MeterRegistry registry;

    @Incoming("kafka-predictions")
    @Blocking
    public Uni<Void> consume(KafkaRecord<String, String> record) {
        LOG.infof("Received message: %s", record.getPayload());

        try {
            JsonObject json = new JsonObject(record.getPayload());
            String transformerFlair = json.getString("transformer_flair", "Unknown");
            double transformerConfidence = json.getDouble("transformer_confidence", 0.0);

            String sklearnFlair = json.getString("sklearn_flair", "Unknown");
            double sklearnConfidence = json.getDouble("sklearn_confidence", 0.0);

            // Update transformer stats
            transformerCounts.merge(transformerFlair, 1, Integer::sum);
            transformerConfidenceSum.merge(transformerFlair, transformerConfidence, Double::sum);

            // Update sklearn stats
            sklearnCounts.merge(sklearnFlair, 1, Integer::sum);
            sklearnConfidenceSum.merge(sklearnFlair, sklearnConfidence, Double::sum);

            // Track agreement
            if (transformerFlair.equals(sklearnFlair)) {
                flairAgreementCount.merge(transformerFlair, 1, Integer::sum);
            }

            // Update confusion matrix
            confusionMatrix
                    .computeIfAbsent(sklearnFlair, k -> new ConcurrentHashMap<>())
                    .merge(transformerFlair, 1, Integer::sum);

            // Update timeline counts. We use the transformer flair as the key.
            String flair = json.getString("transformer_flair", "Unknown");
            String bucket = formatter.format(record.getTimestamp());

            timelineCounts
                    .computeIfAbsent(bucket, k -> new ConcurrentHashMap<>())
                    .merge(flair, 1, Integer::sum);

            // Prometheus counters
            registry.counter("flair_messages_total", "model", "transformer", "flair", transformerFlair).increment();
            registry.counter("flair_messages_total", "model", "sklearn", "flair", sklearnFlair).increment();

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

    @GET
    @Path("/confusion-matrix")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfusionMatrix() {
        JsonObject response = new JsonObject();
        Set<String> allFlairs = new TreeSet<>();
        confusionMatrix.forEach((sk, innerMap) -> {
            allFlairs.add(sk);
            allFlairs.addAll(innerMap.keySet());
        });

        List<String> flairList = new ArrayList<>(allFlairs);
        Collections.sort(flairList); // ensure matrix is ordered consistently

        int size = flairList.size();
        int[][] matrix = new int[size][size];

        for (int i = 0; i < size; i++) {
            String sk = flairList.get(i);
            Map<String, Integer> row = confusionMatrix.getOrDefault(sk, new HashMap<>());
            for (int j = 0; j < size; j++) {
                String tf = flairList.get(j);
                matrix[i][j] = row.getOrDefault(tf, 0);
            }
        }

        response.put("labels", flairList);
        response.put("matrix", matrix);

        return Response.ok(response).build();
    }

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