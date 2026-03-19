package uoc.edu;

import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class FlairConsumerTest {

    /**
     * A Hamcrest matcher that compares Number values with a tolerance,
     * handling both Float and Double JSON representations.
     */
    private static Matcher<Number> closeToNum(double expected, double tolerance) {
        return new BaseMatcher<>() {
            @Override
            public boolean matches(Object actual) {
                if (actual instanceof Number) {
                    return Math.abs(((Number) actual).doubleValue() - expected) <= tolerance;
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("a numeric value within ")
                        .appendValue(tolerance)
                        .appendText(" of ")
                        .appendValue(expected);
            }
        };
    }

    @BeforeEach
    void resetState() {
        BaseResource.transformerCounts.clear();
        BaseResource.sklearnCounts.clear();
        BaseResource.transformerConfidenceSum.clear();
        BaseResource.sklearnConfidenceSum.clear();
        BaseResource.flairAgreementCount.clear();
        BaseResource.timelineCounts.clear();
        BaseResource.confusionMatrix.clear();
        BaseResource.agreementTimeline.clear();

        Arrays.fill(BaseResource.transformerBuckets, 0);
        Arrays.fill(BaseResource.sklearnBuckets, 0);

        BaseResource.bothConfident = 0;
        BaseResource.bothUncertain = 0;
        BaseResource.disagreement = 0;
    }

    // ---------- 1. Statistics endpoint ----------

    @Test
    void testStatisticsCountsAndAverages() {
        // Simulate two messages for "Politics" where both models agree
        BaseResource.transformerCounts.put("Politics", 2);
        BaseResource.sklearnCounts.put("Politics", 2);
        BaseResource.transformerConfidenceSum.put("Politics", 1.70); // avg = 0.85
        BaseResource.sklearnConfidenceSum.put("Politics", 1.44);    // avg = 0.72
        BaseResource.flairAgreementCount.put("Politics", 2);

        given()
            .when().get("/flairs/statistics")
            .then()
            .statusCode(200)
            .body("flairs.Politics.count", equalTo(2))
            .body("flairs.Politics.avg_confidence_transformer", closeToNum(0.85, 0.001))
            .body("flairs.Politics.avg_confidence_sklearn", closeToNum(0.72, 0.001))
            .body("flairs.Politics.agreement_rate", closeToNum(1.0, 0.001));
    }

    @Test
    void testStatisticsMultipleFlairs() {
        BaseResource.transformerCounts.put("Politics", 3);
        BaseResource.sklearnCounts.put("Politics", 3);
        BaseResource.transformerConfidenceSum.put("Politics", 2.55);
        BaseResource.sklearnConfidenceSum.put("Politics", 2.16);
        BaseResource.flairAgreementCount.put("Politics", 3);

        BaseResource.transformerCounts.put("Sports", 1);
        BaseResource.sklearnCounts.put("Sports", 1);
        BaseResource.transformerConfidenceSum.put("Sports", 0.90);
        BaseResource.sklearnConfidenceSum.put("Sports", 0.40);
        BaseResource.flairAgreementCount.put("Sports", 0);

        given()
            .when().get("/flairs/statistics")
            .then()
            .statusCode(200)
            .body("flairs.Politics.count", equalTo(3))
            .body("flairs.Politics.agreement_rate", closeToNum(1.0, 0.001))
            .body("flairs.Sports.count", equalTo(1))
            .body("flairs.Sports.agreement_rate", closeToNum(0.0, 0.001));
    }

    // ---------- 2. Confusion matrix ----------

    @Test
    void testConfusionMatrixPopulatedCorrectly() {
        // Transformer predicted "Politics", sklearn predicted "Politics" twice
        // Transformer predicted "Politics", sklearn predicted "Sports" once
        Map<String, Integer> politicsRow = new ConcurrentHashMap<>();
        politicsRow.put("Politics", 2);
        politicsRow.put("Sports", 1);
        BaseResource.confusionMatrix.put("Politics", politicsRow);

        Map<String, Integer> sportsRow = new ConcurrentHashMap<>();
        sportsRow.put("Sports", 3);
        BaseResource.confusionMatrix.put("Sports", sportsRow);

        given()
            .when().get("/flairs/confusion-matrix")
            .then()
            .statusCode(200)
            .body("labels", hasItems("Politics", "Sports"))
            .body("labels.size()", equalTo(2))
            // Matrix is ordered alphabetically: [Politics, Sports]
            // Row 0 (Politics): [2, 1]
            // Row 1 (Sports):   [0, 3]
            .body("matrix[0][0]", equalTo(2))
            .body("matrix[0][1]", equalTo(1))
            .body("matrix[1][0]", equalTo(0))
            .body("matrix[1][1]", equalTo(3));
    }

    // ---------- 3. Confidence distribution ----------

    @Test
    void testConfidenceDistributionBuckets() {
        // Confidence 0.85 -> bucket index 8 (floor(0.85 * 10) = 8)
        // Confidence 0.72 -> bucket index 7
        // Confidence 0.05 -> bucket index 0
        BaseResource.transformerBuckets[8] = 2;
        BaseResource.transformerBuckets[0] = 1;
        BaseResource.sklearnBuckets[7] = 2;
        BaseResource.sklearnBuckets[0] = 1;

        given()
            .when().get("/flairs/confidence-distribution")
            .then()
            .statusCode(200)
            .body("labels.size()", equalTo(10))
            .body("transformer[0]", equalTo(1))
            .body("transformer[8]", equalTo(2))
            .body("sklearn[7]", equalTo(2))
            .body("sklearn[0]", equalTo(1))
            // All other buckets should be 0
            .body("transformer[5]", equalTo(0))
            .body("sklearn[5]", equalTo(0));
    }

    // ---------- 4. Agreement tracking ----------

    @Test
    void testAgreementSameFlairVsDifferentFlair() {
        // 4 transformer predictions for "Politics", but sklearn agreed only 3 times
        BaseResource.transformerCounts.put("Politics", 4);
        BaseResource.sklearnCounts.put("Politics", 3);
        BaseResource.sklearnCounts.put("Sports", 1);
        BaseResource.transformerConfidenceSum.put("Politics", 3.40);
        BaseResource.sklearnConfidenceSum.put("Politics", 2.16);
        BaseResource.sklearnConfidenceSum.put("Sports", 0.50);
        BaseResource.flairAgreementCount.put("Politics", 3); // 3 out of 4 agreed

        given()
            .when().get("/flairs/statistics")
            .then()
            .statusCode(200)
            .body("flairs.Politics.agreement_rate", closeToNum(0.75, 0.001));
    }

    // ---------- 5. Uncertainty zones ----------

    @Test
    void testUncertaintyZonesBothConfident() {
        // Both models >= 0.6 threshold
        BaseResource.bothConfident = 5;
        BaseResource.bothUncertain = 0;
        BaseResource.disagreement = 0;

        given()
            .when().get("/flairs/uncertainty-zones")
            .then()
            .statusCode(200)
            .body("both_confident", equalTo(5))
            .body("both_uncertain", equalTo(0))
            .body("disagreement", equalTo(0));
    }

    @Test
    void testUncertaintyZonesMixedCategories() {
        // Both confident (both >= 0.6): 3
        // Both uncertain (both < 0.6): 2
        // Disagreement (one >= 0.6, other < 0.6): 4
        BaseResource.bothConfident = 3;
        BaseResource.bothUncertain = 2;
        BaseResource.disagreement = 4;

        given()
            .when().get("/flairs/uncertainty-zones")
            .then()
            .statusCode(200)
            .body("both_confident", equalTo(3))
            .body("both_uncertain", equalTo(2))
            .body("disagreement", equalTo(4));
    }

    // ---------- 6. Agreement timeline ----------

    @Test
    void testAgreementTimelineRatesPerDay() {
        // Day 1: 3 agreed out of 4 total -> rate 0.75
        BaseResource.agreementTimeline.put("2025-06-01", new int[]{3, 4});
        // Day 2: 1 agreed out of 2 total -> rate 0.5
        BaseResource.agreementTimeline.put("2025-06-02", new int[]{1, 2});

        given()
            .when().get("/flairs/agreement-timeline")
            .then()
            .statusCode(200)
            .body("'2025-06-01'", closeToNum(0.75, 0.001))
            .body("'2025-06-02'", closeToNum(0.5, 0.001));
    }

    // ---------- 7. Edge cases ----------

    @Test
    void testEmptyStateReturnsValidResponses() {
        // All state is cleared by @BeforeEach, endpoints should return valid empty responses

        given()
            .when().get("/flairs/statistics")
            .then()
            .statusCode(200)
            .body("flairs", anEmptyMap());

        given()
            .when().get("/flairs/confusion-matrix")
            .then()
            .statusCode(200)
            .body("labels", empty())
            .body("matrix", empty());

        given()
            .when().get("/flairs/uncertainty-zones")
            .then()
            .statusCode(200)
            .body("both_confident", equalTo(0))
            .body("both_uncertain", equalTo(0))
            .body("disagreement", equalTo(0));

        given()
            .when().get("/flairs/agreement-timeline")
            .then()
            .statusCode(200)
            .body("$", anEmptyMap());

        given()
            .when().get("/flairs/confidence-distribution")
            .then()
            .statusCode(200)
            .body("labels.size()", equalTo(10))
            .body("transformer[0]", equalTo(0))
            .body("sklearn[0]", equalTo(0));
    }

    @Test
    void testUnknownFlairsAndZeroConfidence() {
        // Simulate a message with unknown flairs and zero confidence
        BaseResource.transformerCounts.put("Unknown", 1);
        BaseResource.sklearnCounts.put("Unknown", 1);
        BaseResource.transformerConfidenceSum.put("Unknown", 0.0);
        BaseResource.sklearnConfidenceSum.put("Unknown", 0.0);
        BaseResource.flairAgreementCount.put("Unknown", 1);

        // Zero confidence -> bucket index 0
        BaseResource.transformerBuckets[0] = 1;
        BaseResource.sklearnBuckets[0] = 1;

        // Both uncertain (0.0 < 0.6)
        BaseResource.bothUncertain = 1;

        given()
            .when().get("/flairs/statistics")
            .then()
            .statusCode(200)
            .body("flairs.Unknown.count", equalTo(1))
            .body("flairs.Unknown.avg_confidence_transformer", closeToNum(0.0, 0.001))
            .body("flairs.Unknown.avg_confidence_sklearn", closeToNum(0.0, 0.001))
            .body("flairs.Unknown.agreement_rate", closeToNum(1.0, 0.001));

        given()
            .when().get("/flairs/confidence-distribution")
            .then()
            .statusCode(200)
            .body("transformer[0]", equalTo(1))
            .body("sklearn[0]", equalTo(1));

        given()
            .when().get("/flairs/uncertainty-zones")
            .then()
            .statusCode(200)
            .body("both_uncertain", equalTo(1))
            .body("both_confident", equalTo(0))
            .body("disagreement", equalTo(0));
    }
}
