# Presentation guide

Why Are Predictions Late? Diagnosing Streaming Inference on Kubernetes

## 0:00 — An available endpoint can serve stale predictions

The experiment isolates Spark processing from Kafka and HTTP serving. Its useful outcome is event age at callback output, not endpoint availability.

Identify the measurement boundary before showing any latency figure.

## 4:00 — Record current ingress timestamps on replay

Each of twelve events receives a new ingress timestamp and stable fixture ID. Historical source time would measure a different kind of age.

The warm-up event is excluded. Show event IDs and timestamps in experiment/results.json.

## 9:00 — Separate queue, compute and output visibility

Record ingress, batch processing start and result collection. Batch duration includes collecting records, classification and any labeled injected delay.

The classifier runs in the driver callback, not as distributed executor-side inference.

## 15:00 — Remove one delay and look for the next limit

In one local run, removing the injected delay reduced median age from 8.40 to 5.84 seconds. Eleven events were still pending when input stopped.

These are single-run fixture observations with slightly different measured offered rates, not a production benchmark.

## 21:00 — The source admission cap can dominate after compute improves

The source still accepts one file per one-second trigger. Faster classification cannot remove that admission limit; the runner drains and verifies all twelve events afterward.

Distinguish pending work at input completion from lost records.

## 26:00 — Questions and buffer

Single run, four training texts, file source, driver-side classifier in foreachBatch, local[2], two-CPU/3GB container. Injected delay is a labeled intervention, not measured model inference cost. Result visibility is collection into the callback output, not a Quarkus endpoint. Kafka, Kubernetes and production ML quality are outside this isolation experiment.

## Commands

```sh
docker build -t talk-stream-experiment:local experiment
docker run --rm --cpus 2 --memory 3g -v "$PWD/experiment:/lab" talk-stream-experiment:local
```

## Observed result

Actual Spark 3.5.3 Structured Streaming experiment passed with a fitted CountVectorizer/MultinomialNB fixture and twelve events per case. Baseline had no pending events when input stopped. Injected overload had median age about 8.4 seconds; removing the injected delay reduced it to about 5.8 seconds but retained eleven pending events because the source still admitted one file per trigger.

For 25 minutes, shorten setup and keep one trace plus the failure result. A backup recording must show a real run, not synthetic output.
