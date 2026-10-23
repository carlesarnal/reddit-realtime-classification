# Verification — October 23, 2026

Actual Spark 3.5.3 Structured Streaming experiment passed with a fitted CountVectorizer/MultinomialNB fixture and twelve events per case. Baseline had no pending events when input stopped. Injected overload had median age about 8.4 seconds; removing the injected delay reduced it to about 5.8 seconds but retained eleven pending events because the source still admitted one file per trigger.

```sh
docker build -t talk-stream-experiment:local experiment
docker run --rm --cpus 2 --memory 3g -v "$PWD/experiment:/lab" talk-stream-experiment:local
```

Single run, four training texts, file source, driver-side classifier in foreachBatch, local[2], two-CPU/3GB container. Injected delay is a labeled intervention, not measured model inference cost. Result visibility is collection into the callback output, not a Quarkus endpoint. Kafka, Kubernetes and production ML quality are outside this isolation experiment.
