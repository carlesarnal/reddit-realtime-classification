# Why Are Predictions Late? Diagnosing Streaming Inference on Kubernetes

Actual Spark 3.5.3 Structured Streaming experiment passed with a fitted CountVectorizer/MultinomialNB fixture and twelve events per case. Baseline had no pending events when input stopped. Injected overload had median age about 8.4 seconds; removing the injected delay reduced it to about 5.8 seconds but retained eleven pending events because the source still admitted one file per trigger.

## Run

```sh
docker build -t talk-stream-experiment:local experiment
docker run --rm --cpus 2 --memory 3g -v "$PWD/experiment:/lab" talk-stream-experiment:local
```

## Evidence and boundaries

Single run, four training texts, file source, driver-side classifier in foreachBatch, local[2], two-CPU/3GB container. Injected delay is a labeled intervention, not measured model inference cost. Result visibility is collection into the callback output, not a Quarkus endpoint. Kafka, Kubernetes and production ML quality are outside this isolation experiment.

Observed machine-readable results: [experiment/results.json](experiment/results.json).

All containers/processes created by the runner are cleaned up on normal completion and handled failures. An externally killed process may need the explicit Compose cleanup command. Use only disposable local test resources.

## Present

Open `presentation.html`; arrows/Page keys navigate and N toggles notes. Use `PRESENTATION_GUIDE.md` for timing and the actual commands.
