package uoc.edu;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class BaseResource {

    protected static final Logger LOG = Logger.getLogger(BaseResource.class);

    protected static final Map<String, Integer> transformerCounts = new ConcurrentHashMap<>();
    protected static final Map<String, Integer> sklearnCounts = new ConcurrentHashMap<>();
    protected static final Map<String, Double> transformerConfidenceSum = new ConcurrentHashMap<>();
    protected static final Map<String, Double> sklearnConfidenceSum = new ConcurrentHashMap<>();
    protected static final Map<String, Integer> flairAgreementCount = new ConcurrentHashMap<>();
    protected static final Map<String, Map<String, Integer>> timelineCounts = new ConcurrentHashMap<>();
    protected static final Map<String, Map<String, Integer>> confusionMatrix = new ConcurrentHashMap<>();
    protected static final Map<String, int[]> agreementTimeline = new ConcurrentHashMap<>();
    protected static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("UTC"));

    protected static final int BUCKET_COUNT = 10;
    protected static final int[] transformerBuckets = new int[BUCKET_COUNT];
    protected static final int[] sklearnBuckets = new int[BUCKET_COUNT];

    protected static int bothConfident = 0;
    protected static int bothUncertain = 0;
    protected static int disagreement = 0;

    private static final double CONFIDENCE_THRESHOLD = 0.6;

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

            updateStatistics(transformerFlair, sklearnFlair, transformerConfidence, sklearnConfidence);
            updateConfusionMatrix(transformerFlair, sklearnFlair);

            String bucket = formatter.format(record.getTimestamp());
            updateTimelineCounts(transformerFlair, bucket);
            updateConfidenceDistribution(transformerConfidence, sklearnConfidence);
            updateModelUncertaintyZone(transformerConfidence, sklearnConfidence);
            updateAgreementTimeline(transformerFlair, sklearnFlair, record.getTimestamp());

            record.ack();
        } catch (Exception e) {
            LOG.error("Failed to parse message", e);
        }

        return Uni.createFrom().nullItem();
    }

    private void updateStatistics(String transformerFlair, String sklearnFlair, double transformerConfidence, double sklearnConfidence) {
        transformerCounts.merge(transformerFlair, 1, Integer::sum);
        transformerConfidenceSum.merge(transformerFlair, transformerConfidence, Double::sum);
        sklearnCounts.merge(sklearnFlair, 1, Integer::sum);
        sklearnConfidenceSum.merge(sklearnFlair, sklearnConfidence, Double::sum);

        if (transformerFlair.equals(sklearnFlair)) {
            flairAgreementCount.merge(transformerFlair, 1, Integer::sum);
        }
    }

    private void updateConfusionMatrix(String transformerFlair, String sklearnFlair) {
        confusionMatrix
                .computeIfAbsent(transformerFlair, k -> new ConcurrentHashMap<>())
                .merge(sklearnFlair, 1, Integer::sum);
    }

    private void updateTimelineCounts(String flair, String bucket) {
        timelineCounts
                .computeIfAbsent(bucket, k -> new ConcurrentHashMap<>())
                .merge(flair, 1, Integer::sum);
    }

    private void updateConfidenceDistribution(double transformerConfidence, double sklearnConfidence) {
        int tBucket = Math.min((int) (transformerConfidence * BUCKET_COUNT), BUCKET_COUNT - 1);
        int sBucket = Math.min((int) (sklearnConfidence * BUCKET_COUNT), BUCKET_COUNT - 1);

        synchronized (transformerBuckets) {
            transformerBuckets[tBucket]++;
        }
        synchronized (sklearnBuckets) {
            sklearnBuckets[sBucket]++;
        }
    }

    private void updateModelUncertaintyZone(double transformerConfidence, double sklearnConfidence) {
        if (transformerConfidence >= CONFIDENCE_THRESHOLD && sklearnConfidence >= CONFIDENCE_THRESHOLD) {
            bothConfident++;
        } else if (transformerConfidence < CONFIDENCE_THRESHOLD && sklearnConfidence < CONFIDENCE_THRESHOLD) {
            bothUncertain++;
        } else {
            disagreement++;
        }
    }

    private void updateAgreementTimeline(String transformerFlair, String sklearnFlair, java.time.Instant recordCreation) {
        String bucket = formatter.format(recordCreation);
        agreementTimeline.computeIfAbsent(bucket, k -> new int[2]);

        int[] stats = agreementTimeline.get(bucket);
        if (transformerFlair.equals(sklearnFlair)) {
            stats[0]++;
        }
        stats[1]++;
    }
}
