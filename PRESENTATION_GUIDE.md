# Presentation guide

Why Are Predictions Late? Diagnosing Streaming Inference on Kubernetes

## Audience and central claim

The existing classification pipeline connects Kafka, Spark inference and a Quarkus results API. An available API can serve predictions about old inputs. Adding replicas to that API does not necessarily improve upstream processing. Apicurio Registry supports schema integration, but a schema cannot establish that a prediction is timely.

## 30-minute run of show

### 0:00 — HTTP 200 can serve old predictions

Say: The existing classification pipeline connects Kafka, Spark inference and a Quarkus results API. An available API can serve predictions about old inputs. Adding replicas to that API does not necessarily improve upstream processing. Apicurio Registry supports schema integration, but a schema cannot establish that a prediction is timely.

Show the corresponding diagram, source record or actual command output. Pause to identify the observation that supports the claim.

### 4:00 — Record ingress and query visibility

Say: Record ingress time, processing start, processing end and query visibility with a stable event identity. The new CSV analyzer calculates queue, processing and visibility intervals and their sum. It rejects empty observations, duplicate IDs, naive timestamps and reversed boundaries. Its unit fixtures prove arithmetic and validation only, not a measured throughput or latency figure.

Show the corresponding diagram, source record or actual command output. Pause to identify the observation that supports the claim.

### 9:00 — Separate queue, compute and visibility delay

Say: Use fixed inputs and stamp their current ingress time; historical source timestamps are a different age metric. Keep model, resources and input distribution comparable. Run a sustainable baseline, then overload, then change a single processing setting. Separate warm-up and observation windows. A micro-batch interval can add scheduling delay, but reducing it does not create inference capacity.

Show the corresponding diagram, source record or actual command output. Pause to identify the observation that supports the claim.

### 15:00 — Change one setting at a time

Say: The earlier OCX presentation supports the application and speaker background. It is not a recording of this new controlled experiment. The repository now contains the measurement contract and analyzer, while actual replay/load measurements remain pending. Report observed input/processing rates and backlog alongside per-event age before recommending a capacity change.

Show the corresponding diagram, source record or actual command output. Pause to identify the observation that supports the claim.

### 21:00 — Scale the limiting stage

Say: Use the evidence boundary to choose the next action.

Show the corresponding diagram, source record or actual command output. Pause to identify the observation that supports the claim.

### 26:00 — Questions and buffer

Ask what the demonstration establishes and what it leaves open. Do not add another feature if time is short.

## Setup and fallback

```sh
python3 -m unittest discover -s talk -p "test_*.py" -v
```

Timestamp analyzer and unit fixtures prepared. Fixtures are synthetic correctness inputs, not performance results. The fixed-replay Spark load experiment and fresh Kubernetes execution remain pending.

Prebuild/download dependencies. Keep a recording of an actual successful run; if unavailable, use the documented walkthrough and say it is not live execution. Never substitute fabricated success output.

## Questions to prepare

- What is the simplest alternative?
- Which failure was actually exercised?
- Which guarantee belongs to the application rather than the infrastructure?
- Which result changes with versions or workload?

## 25-minute adaptation

Use one central trace, shorten setup and reserve three minutes for questions. Move the secondary implementation example to backup.
