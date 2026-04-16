package uoc.edu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class BaseResource {

    protected static final Logger LOG = Logger.getLogger(BaseResource.class);

    @ConfigProperty(name = "apicurio.registry.url")
    String registryUrl;

    @ConfigProperty(name = "apicurio.registry.group-id")
    String groupId;

    @ConfigProperty(name = "apicurio.registry.artifact-id")
    String artifactId;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonSchema jsonSchema;

    @PostConstruct
    void loadSchema() {
        try {
            String url = registryUrl + "/apis/registry/v3/groups/" + groupId
                    + "/artifacts/" + artifactId + "/versions/latest/content";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            jsonSchema = factory.getSchema(response.body());
            LOG.infof("Loaded schema '%s' from Apicurio Registry for validation", artifactId);
        } catch (Exception e) {
            LOG.warnf("Could not load schema from Apicurio Registry: %s. Validation disabled.", e.getMessage());
        }
    }

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
            // Validate against JSON Schema from Apicurio Registry
            if (jsonSchema != null) {
                JsonNode node = objectMapper.readTree(record.getPayload());
                Set<ValidationMessage> errors = jsonSchema.validate(node);
                if (!errors.isEmpty()) {
                    LOG.warnf("Schema validation failed: %s", errors);
                    return Uni.createFrom().nullItem();
                }
            }

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
