# Presentation guide

Why Are Predictions Late? Diagnosing Streaming Inference on Kubernetes

## 0:00 — An available endpoint can serve stale predictions

Use the corresponding assertion or result record, explain what changes, and ask which boundary the observation proves.

## 4:00 — Record current ingress timestamps on replay

Use the corresponding assertion or result record, explain what changes, and ask which boundary the observation proves.

## 9:00 — Separate queue, compute and output visibility

Use the corresponding assertion or result record, explain what changes, and ask which boundary the observation proves.

## 15:00 — Remove one delay and look for the next limit

Use the corresponding assertion or result record, explain what changes, and ask which boundary the observation proves.

## 21:00 — The source admission cap can dominate after compute improves

Use the corresponding assertion or result record, explain what changes, and ask which boundary the observation proves.

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
