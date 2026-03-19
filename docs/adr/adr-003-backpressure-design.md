# ADR-003: Backpressure and Resilience Design

## Status

Accepted

## Context

The pipeline connects external systems (Reddit API) to internal processing (Kafka, Spark, Quarkus). Each component can experience overload or downstream unavailability:

- Reddit API has rate limits and occasional outages
- Spark inference is CPU-bound and can fall behind if input volume spikes
- Apicurio Registry (used by model-metadata) may be temporarily unavailable
- The Quarkus consumer must not overwhelm itself if predictions arrive faster than it can process

Without backpressure, any of these scenarios can cascade into OOM errors, stuck consumers, or lost data.

## Decision

Each component uses the backpressure mechanism most natural to its runtime:

### Producer — Exponential Backoff with Jitter
On Reddit API or Kafka errors, the producer backs off exponentially: 1s base, 2x growth per consecutive failure, capped at 5 minutes. A 25% random jitter prevents thundering herd when multiple producer replicas recover simultaneously. On successful cycle, the counter resets and the normal 5-minute poll interval resumes.

### Spark — maxOffsetsPerTrigger
The Structured Streaming source is configured with `maxOffsetsPerTrigger=100`. This limits each micro-batch to 100 Kafka messages, preventing the driver from pulling more data than it can process within the 1-minute trigger interval. If the queue grows, Spark processes it incrementally rather than attempting to load everything at once.

### Consumer — SmallRye Pull-Based Messaging
The Quarkus consumer uses SmallRye Reactive Messaging, which is inherently pull-based. The consumer requests the next message only after completing the current one (due to the `@Blocking` annotation). This provides natural backpressure — if processing slows down, Kafka consumer lag increases but the application doesn't OOM.

### Model-Metadata — Retry with Linear Backoff
When Apicurio Registry is unavailable, the ModelController retries validation up to 3 times with linear backoff (1s, 2s, 3s). After exhaustion, it returns HTTP 503 (Service Unavailable) rather than 400 or 500, signaling to clients that the issue is temporary.

## Consequences

**Benefits:**
- Each component degrades gracefully under load rather than failing catastrophically
- Exponential backoff on the producer prevents Reddit API rate limit bans
- Spark batch size limiting prevents OOM during inference backlog
- 503 responses from model-metadata allow clients to implement their own retry logic

**Tradeoffs:**
- `maxOffsetsPerTrigger=100` limits throughput. If the subreddit has very high volume, this may cause growing consumer lag. Tunable via environment variable if needed.
- Producer backoff means posts may be delayed during Reddit API issues. Acceptable since the pipeline is near-real-time, not hard-real-time.
- Linear backoff on model-metadata is simple but not optimal for long outages. Acceptable since the service is not on the critical path for the main pipeline.

## Alternatives Considered

- **Rate limiter (token bucket)** on producer — More precise but adds complexity. Reddit API's own rate limiting makes this redundant.
- **Spark auto-scaling** (dynamic executor allocation) — Better for sustained load but adds infrastructure complexity. `maxOffsetsPerTrigger` is simpler and sufficient for current scale.
- **Circuit breaker library (Resilience4j)** for model-metadata — Overkill for a single downstream dependency. Simple retry is sufficient.
