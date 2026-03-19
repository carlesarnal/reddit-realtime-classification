# ADR-005: Observability Strategy

## Status

Accepted

## Context

The pipeline processes Reddit posts through 3 distributed components (Producer, Spark, Consumer) connected by Kafka. Without observability:

- We can't trace a single post's journey through the pipeline
- We don't know how long each stage takes
- Model performance degradation (drift) is invisible
- Failures in the Spark job are only visible in Spark driver logs
- There's no alerting on error rates or processing latency

For a production ML pipeline, observability covers three pillars: **traces** (request flow), **metrics** (aggregated measurements), and **logs** (discrete events).

## Decision

### Distributed Tracing — OpenTelemetry + Jaeger

All three components are instrumented with OpenTelemetry, exporting traces via OTLP/gRPC to a Jaeger all-in-one collector:

- **Producer**: Manual spans for Reddit API calls (`reddit-api-search`) and Kafka produce (`produce-reddit-post`). W3C `traceparent` headers injected into Kafka message headers for cross-service propagation.
- **Spark**: Manual spans wrapping the dual-model UDF — `dual-model-inference` parent span with `transformer-inference` and `sklearn-inference` child spans. Prediction attributes (flair, confidence, agreement) attached to spans.
- **Consumer**: `quarkus-opentelemetry` extension auto-instruments Kafka consumption and REST endpoints. No manual instrumentation needed.

We chose manual instrumentation for Python (Producer, Spark) because auto-instrumentation libraries don't cover kafka-python headers injection or PySpark UDF contexts. Quarkus auto-instrumentation is sufficient because SmallRye Kafka and REST are fully supported.

### Metrics — Prometheus + Custom Counters

The Quarkus consumer exposes metrics at `/q/metrics` via Micrometer + Prometheus registry:

- `flair_messages_total{model, flair}` — Counter per model per flair
- `flair_message_errors_total` — Error counter
- `flair_processing_latency_seconds` — Histogram of per-message processing time
- `model_agreement_rate` — Gauge tracking rolling agreement between models
- `pipeline_messages_total{stage}` — Counter with stage label
- `model_confidence_latest{model}` — Latest confidence per model

Prometheus scrapes both the flair-consumer and model-metadata services.

### Dashboards — Grafana

Three pre-provisioned Grafana dashboards:

1. **Pipeline Overview** — Message rates, error rate, flair distribution, processing latency (p50/p95/p99), agreement rate gauge, throughput
2. **Model Comparison** — Transformer vs sklearn prediction distribution, per-model flair breakdown
3. **System Health** — JVM heap, GC pauses, HTTP latency, CPU, threads, uptime

Grafana is configured with both Prometheus and Jaeger as datasources, allowing correlation between metrics and traces.

### Collector — Jaeger All-in-One

We chose Jaeger all-in-one (in-memory storage) over a full OpenTelemetry Collector because:
- Simpler deployment (single container)
- Built-in UI for trace visualization
- Sufficient for the current scale (traces are retained in memory)
- Can be replaced with a production Jaeger deployment (with Elasticsearch/Cassandra backend) when needed

## Consequences

**Benefits:**
- End-to-end visibility: a single Reddit post can be traced from API call through inference to consumption
- Model performance is quantified: latency per model, agreement rate, confidence distributions
- Grafana dashboards provide at-a-glance pipeline health
- OTel spans on inference reveal exactly how long each model takes, enabling informed cost/accuracy tradeoffs
- Prometheus metrics enable alerting (e.g., error rate > threshold, agreement rate dropping)

**Tradeoffs:**
- Jaeger all-in-one uses in-memory storage — traces are lost on restart. Acceptable for development; production would need persistent storage.
- Spark UDF tracing creates spans on the executor, not the driver. These spans are independent (not child spans of the Kafka consumer trace) because PySpark UDFs don't propagate OTel context across the driver/executor boundary. Correlation is done via the `reddit.post.id` attribute.
- Three additional K8s deployments (Jaeger, Prometheus, Grafana) increase cluster resource usage by ~1.5 GB memory total.
- Producer trace context in Kafka headers adds ~100 bytes per message overhead.

## Alternatives Considered

- **ELK Stack (Elasticsearch + Logstash + Kibana)** — Heavier, more suited for log aggregation. OTel + Jaeger is purpose-built for distributed tracing.
- **Datadog / New Relic** — SaaS solutions with better UX but vendor lock-in and cost. Not appropriate for an open-source demo project.
- **OpenTelemetry Collector** — More flexible (can fan out to multiple backends) but adds another component. Jaeger all-in-one is simpler for the current setup.
- **Metrics only (no tracing)** — Cheaper but loses the ability to debug individual request flows. Critical for understanding why specific posts get misclassified.
