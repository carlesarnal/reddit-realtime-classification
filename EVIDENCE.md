# Evidence and claim boundaries

Timestamp analyzer and unit fixtures prepared. Fixtures are synthetic correctness inputs, not performance results. The fixed-replay Spark load experiment and fresh Kubernetes execution remain pending.

## Availability is not freshness

The existing classification pipeline connects Kafka, Spark inference and a Quarkus results API. An available API can serve predictions about old inputs. Adding replicas to that API does not necessarily improve upstream processing. Apicurio Registry supports schema integration, but a schema cannot establish that a prediction is timely.

## Give every event a timeline

Record ingress time, processing start, processing end and query visibility with a stable event identity. The new CSV analyzer calculates queue, processing and visibility intervals and their sum. It rejects empty observations, duplicate IDs, naive timestamps and reversed boundaries. Its unit fixtures prove arithmetic and validation only, not a measured throughput or latency figure.

## Control the replay

Use fixed inputs and stamp their current ingress time; historical source timestamps are a different age metric. Keep model, resources and input distribution comparable. Run a sustainable baseline, then overload, then change a single processing setting. Separate warm-up and observation windows. A micro-batch interval can add scheduling delay, but reducing it does not create inference capacity.

## What remains to measure

The earlier OCX presentation supports the application and speaker background. It is not a recording of this new controlled experiment. The repository now contains the measurement contract and analyzer, while actual replay/load measurements remain pending. Report observed input/processing rates and backlog alongside per-event age before recommending a capacity change.
