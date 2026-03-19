# ADR-002: Dead Letter Queue Strategy

## Status

Accepted

## Context

The pipeline has three stages where message processing can fail:

1. **Producer** — Reddit API errors, Kafka broker unavailable, serialization failures
2. **Spark Inference** — Model loading errors, OOM on large text, malformed input
3. **Consumer** — Malformed JSON from predictions topic, unexpected schema changes

Previously, all failures were either silently dropped (Spark) or logged and skipped (Consumer). This means data loss with no way to investigate, replay, or alert.

## Decision

Each component has a DLQ strategy appropriate to its failure mode:

### Consumer (Quarkus)
Use SmallRye Kafka's built-in `failure-strategy=dead-letter-queue`. Failed messages are automatically forwarded to `predictions-dlq` topic with original headers plus error metadata. This is configuration-only — no code changes needed.

### Spark Inference
Wrap the `dual_model_prediction` UDF in try/except. On failure, return a JSON record with a `__dlq: true` flag and the error message. The Spark output is split into two streams: successful predictions go to `kafka-predictions`, DLQ records go to `reddit-inference-dlq`. Each stream has its own checkpoint directory.

### Producer
On persistent Kafka send failures (after the built-in 5 retries), the errback writes the failed post data to a local JSONL file (`/tmp/producer-dlq.jsonl`). We chose a file over a Kafka DLQ here because if Kafka itself is down, we can't write to a DLQ topic either.

## Consequences

**Benefits:**
- No silent data loss — every failure is captured with context
- DLQ topics have 7-day retention, giving time to investigate and replay
- Consumer DLQ is zero-code (SmallRye handles it)
- Spark DLQ preserves the original message content alongside the error

**Tradeoffs:**
- Spark uses a `__dlq` flag in the JSON payload rather than separate error/success schemas. This is pragmatic but means DLQ consumers must filter on this flag.
- Producer DLQ is a local file, not a topic. It won't survive pod restarts unless the path is on a PersistentVolume. Acceptable for now since producer Kafka failures are rare (broker is in the same namespace).
- Two additional Kafka topics to manage (`predictions-dlq`, `reddit-inference-dlq`).

## Alternatives Considered

- **Retry-forever** — Risk of blocking the entire pipeline on a single bad message.
- **Skip and log** — Previous behavior. No way to replay or alert.
- **Separate error topic with structured error schema** — More robust but adds schema management overhead. Can evolve to this later if DLQ volume warrants it.
