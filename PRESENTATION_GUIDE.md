# Presentation Guide - OCXConf 2026

**Session:** Real-time Content Classification on Kubernetes with Open Source  
**Speaker:** Carles Arnal - Principal Software Engineer, IBM  
**Room:** Studio 6  
**Date:** Tuesday, April 14, 2026

---

## Pre-Presentation Setup (do all of this BEFORE the talk)

### Infrastructure (30 min before)

```bash
# Start Minikube
minikube start --memory=8g --cpus=4

# Create namespace
kubectl create namespace reddit-realtime

# Install Strimzi operator
kubectl apply -f https://strimzi.io/install/latest?namespace=reddit-realtime -n reddit-realtime

# Create Reddit API credentials secret
kubectl create secret generic reddit-api-credentials \
  --from-literal=client-id=YOUR_REDDIT_CLIENT_ID \
  --from-literal=client-secret=YOUR_REDDIT_CLIENT_SECRET \
  -n reddit-realtime

# Deploy Kafka cluster (wait for Strimzi operator to be ready first)
kubectl wait --for=condition=available deployment/strimzi-cluster-operator -n reddit-realtime --timeout=120s
kubectl apply -f runtime/kafka/kafka_cluster.yaml

# Wait for Kafka to be ready, then create topics
kubectl wait kafka/reddit-posts --for=condition=Ready -n reddit-realtime --timeout=300s

kubectl apply -f - <<EOF
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: reddit-stream
  namespace: reddit-realtime
  labels:
    strimzi.io/cluster: reddit-posts
spec:
  partitions: 1
  replicas: 1
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: kafka-predictions
  namespace: reddit-realtime
  labels:
    strimzi.io/cluster: reddit-posts
spec:
  partitions: 1
  replicas: 1
EOF

# Install Spark operator (must watch spark-operator namespace)
helm repo add spark-operator https://kubeflow.github.io/spark-operator
helm repo update
helm install spark-operator spark-operator/spark-operator \
  --namespace spark-operator --create-namespace \
  --set 'spark.jobNamespaces={spark-operator}' --wait
```

### Build container images (20 min before)

Build arm64 images locally and load them into Minikube:

```bash
# Build all 3 images
podman build --platform linux/arm64 -t reddit-producer:local -f runtime/kafka/producer/Dockerfile runtime/kafka/producer/
podman build --platform linux/arm64 -t spark-inference:local -f runtime/spark/Dockerfile runtime/spark/
cd runtime/kafka/consumer/flair-consumer && mvn package -DskipTests -q && \
  podman build --platform linux/arm64 -t predictions-consumer:local -f src/main/docker/Dockerfile.jvm . && cd -

# Load into Minikube
minikube image load reddit-producer:local
minikube image load spark-inference:local
minikube image load predictions-consumer:local
```

### Pre-deploy the pipeline (15 min before)

Deploy everything **except** the producer so the pipeline is warm and ready. The producer is what you'll start live to trigger data flow.

```bash
# Deploy Apicurio Registry (schema governance)
kubectl apply -f runtime/registry/apicurio-registry.yaml

# Deploy Spark inference job (takes time to load models — do this early)
kubectl apply -f runtime/spark/reddit_flair_spark_inference.yaml

# Deploy Quarkus consumer + dashboard
kubectl apply -f runtime/kafka/consumer/flair_consumer.yaml

# Wait for deployments to be ready BEFORE setting up port-forwarding
kubectl wait --for=condition=available deployment/apicurio-registry -n reddit-realtime --timeout=120s
kubectl wait --for=condition=available deployment/predictions-consumer -n reddit-realtime --timeout=120s

# Set up port-forwarding
kubectl port-forward -n reddit-realtime svc/predictions-consumer 8080:80 &
kubectl port-forward -n reddit-realtime svc/apicurio-registry 8081:8080 &
sleep 3

# Register schemas
REGISTRY_URL=http://localhost:8081 schemas/register-schemas.sh
```

### Verify everything is ready

```bash
kubectl get pods -n reddit-realtime
kubectl get pods -n spark-operator
# All pods should be Running before you start
```

### Screen layout

**Terminal (2 panes):**

| Pane | Purpose |
|------|---------|
| T1   | kubectl commands + producer deploy |
| T2   | Producer logs (once started) |

**Browser tabs (pre-load):**

1. `http://localhost:8080/metrics.html` — Main dashboard
2. `http://localhost:8080/confusion-matrix.html` — Model comparison heatmap
3. `http://localhost:8080/confidence-distribution.html` — Confidence histograms
4. `http://localhost:8080/model-uncertainty.html` — Uncertainty zones
5. `http://localhost:8080/agreement-over-time.html` — Agreement trend
6. `http://localhost:8080/flair-drift.html` — Data drift detection

---

## Part 1 — Introduction (5 min)

**What to say:**

- Introduce yourself: Principal Software Engineer at IBM, working on Apicurio Registry.
- Frame the problem: real-time data processing is critical in finance, social media, cybersecurity. Classifying content as it arrives is a common need.
- Introduce the case study: classifying Reddit posts from r/AskEurope into 13 flair categories in real-time.
- Emphasize: 100% open source stack, deployable on any Kubernetes cluster.

**Show the architecture:**

```
Reddit API ──> Kafka Producer ──> [reddit-stream] ──> Spark Streaming
                                       │                    │
                               Apicurio Registry      Dual ML inference
                              (schema governance)    (Transformer + sklearn)
                                                         │
                                                    [kafka-predictions]
                                                         │
                                                    Quarkus Consumer ──> Dashboard
```

**Key talking points:**

- Two ML models run in parallel: a fine-tuned DistilRoBERTa transformer and Logistic Regression with TF-IDF + LSA.
- Comparing two models gives richer insight: agreement rates, uncertainty zones, confusion matrices — without needing ground-truth labels.
- Apicurio Registry enforces data contracts between components via JSON Schema.
- Everything runs on Kubernetes using Strimzi (Kafka operator) and the Spark operator.

---

## Part 2 — The ML Models (5 min)

**What to say:**

- The transformer model is a fine-tuned DistilRoBERTa (6 layers, 12 attention heads, 768 hidden dimensions). Trained on labeled r/AskEurope posts.
- The sklearn model uses TF-IDF vectorization, then LSA for dimensionality reduction, then Logistic Regression.
- Both models output a predicted flair + a confidence score.
- Running two different model families lets us measure agreement and identify uncertain predictions — a practical alternative to manual labeling.

**Optionally show:**

- The training notebook: `model/model_training.ipynb`
- Model artifacts: `model/reddit_flair_classifier/` (DistilRoBERTa), `model/reddit_classifier.pkl` (Logistic Regression)

---

## Part 3 — Schema Governance with Apicurio Registry (5 min)

**What to say:**

- Before data flows through the pipeline, we define contracts. Apicurio Registry stores and enforces JSON Schemas for our Kafka topics.
- Two schemas: `reddit-stream-value` (producer output) and `kafka-predictions-value` (inference output).
- This prevents schema drift — if someone changes the producer format, the consumer won't silently break.
- Apicurio supports backward compatibility rules, so schemas can evolve safely.

**What to show:**

```bash
# Show registered schemas
curl -s http://localhost:8081/apis/registry/v3/groups/reddit-realtime/artifacts | python3 -m json.tool
```

- Briefly show `schemas/reddit-stream-value.json` and `schemas/kafka-predictions-value.json`.

---

## Part 4 — Live Demo: Start the Pipeline (10 min)

### Show the pre-deployed state

**In T1:**

```bash
kubectl get pods -n reddit-realtime
```

**What to say:**

- "We have Kafka (via Strimzi), Apicurio Registry, Spark, and the Quarkus consumer already running. The Spark job has loaded both ML models and is waiting for data. The only missing piece is the producer."

### Start the producer

**In T1:**

```bash
kubectl apply -f runtime/kafka/producer/reddit_posts_processor.yaml
```

**What to say:**

- "This Python pod connects to the Reddit API, fetches posts from r/AskEurope, cleans the text — removes URLs, stopwords, does lemmatization — and sends them to Kafka."

**In T2, tail the logs:**

```bash
kubectl logs -n reddit-realtime -f deployment/kafka-producer
```

- Watch for log lines showing posts being sent to Kafka.

### Verify end-to-end flow

```bash
curl -s http://localhost:8080/flairs/statistics | python3 -m json.tool
```

- Show the JSON: per-flair counts, average confidence for both models, agreement rates.

---

## Part 5 — How It Works (5 min)

Explain the data journey and key design decisions while data flows in the background. Share the GitHub repo link for anyone who wants to see the code.

### The Data Journey

Walk through the 4 stages:

1. **Collect & clean** — Fetch posts from Reddit API, strip URLs/stopwords/punctuation, lemmatize, concatenate title + body + comments + domain.
2. **Validate & stream** — Validate against JSON Schema in Apicurio Registry, publish to Kafka topic `reddit-stream`.
3. **Dual inference** — Spark consumes in micro-batches, runs Transformer (tokenize → forward pass → softmax) and sklearn (TF-IDF → LSA → classify) in parallel, outputs both predictions + confidence scores.
4. **Consume & analyze** — Quarkus consumer reads predictions, computes real-time statistics (agreement, confusion matrix, confidence distributions, uncertainty zones), serves dashboards via REST.

### Key Design Decisions

- **Inference inside Spark** — Models loaded once per executor as UDFs. Avoids model-serving hop latency.
- **In-memory analytics** — ConcurrentHashMaps for speed. State lost on restart (production would use a time-series DB).
- **Schema-first contracts** — JSON Schemas registered in Apicurio before data flows. Components evolve independently.
- **Lazy model loading** — Models loaded on first use, not at import time. Sidesteps PySpark UDF serialization issues.

---

## Part 6 — Dashboard Walkthrough (10 min)

Switch to the browser. Dashboards auto-refresh, so data will appear live.

### Main Metrics (`/metrics.html`)

5 bar charts: flair distribution, average confidence, model comparison, agreement rate, confidence gap.

**Say:** "This is the operational overview. You can immediately see which flairs are easy (high agreement, high confidence) and which are ambiguous."

### Confusion Matrix (`/confusion-matrix.html`)

D3.js heatmap — transformer predictions on X axis, sklearn on Y axis.

**Say:** "The diagonal shows agreement. Off-diagonal cells reveal systematic differences between the models."

### Confidence Distribution (`/confidence-distribution.html`)

Stacked histogram comparing both models.

**Say:** "The transformer tends to be more polarized — very confident or not at all. The sklearn model is more evenly distributed. This is typical of neural networks vs linear models."

### Model Uncertainty (`/model-uncertainty.html`)

Doughnut chart with three zones: Both Confident (green), Both Uncertain (orange), Disagreement (crimson).

**Say:** "This is the most actionable chart. The 'disagreement' zone highlights posts where one model is confident but the other isn't — candidates for manual review or retraining."

### Agreement Over Time (`/agreement-over-time.html`)

Line chart showing daily agreement rate.

**Say:** "If agreement drops over time, it could signal data drift."

### Flair Drift (`/flair-drift.html`)

Multi-line chart with one line per flair showing daily frequency.

**Say:** "This helps detect data drift. A sudden spike or drop might reflect a real-world event or a change in subreddit behavior."

---

## Part 7 — Production Considerations & Wrap Up (5 min)

**What to say:**

- **Observability:** In production, add Prometheus + Grafana for metrics and Jaeger for distributed tracing. Quarkus has built-in support for all three.
- **Failure handling:** The consumer uses SmallRye's Dead Letter Queue for failed messages. In production, add DLQs at every pipeline stage.
- **Scaling:** Kafka topics can be partitioned for parallel processing. Spark executors scale horizontally. The Quarkus consumer can be replicated.
- **Schema evolution:** Apicurio Registry supports backward/forward compatibility rules, so schemas can evolve without breaking consumers.
- **Extensibility:** Swap Reddit for any event source (Twitter, IoT, logs). Swap the models. The pipeline pattern is the same.
- **All open source:** Strimzi, Spark Operator, Quarkus, Kafka, Apicurio Registry, PyTorch, scikit-learn — no vendor lock-in.

**Key takeaways:**

1. Building real-time ML pipelines with open source is achievable and practical.
2. Running dual models provides richer observability than a single model.
3. Schema governance (Apicurio Registry) prevents silent contract violations.
4. Kubernetes operators (Strimzi, Spark) simplify deployment and operations.

---

## Troubleshooting Quick Reference

| Problem | Fix |
|---------|-----|
| Pods stuck in Pending | `kubectl describe pod <name> -n reddit-realtime` |
| No data in dashboard | Check producer logs: `kubectl logs -f deployment/kafka-producer -n reddit-realtime` |
| Spark job failing | Check driver: `kubectl logs spark-reddit-inference-driver -n spark-operator` |
| Port-forward dropped | Re-run: `kubectl port-forward -n reddit-realtime svc/predictions-consumer 8080:80` |
| Apicurio schemas lost | In-memory storage; re-register: `REGISTRY_URL=http://localhost:8081 schemas/register-schemas.sh` |
| Producer CreateContainerConfigError | Secret missing: `kubectl create secret generic reddit-api-credentials ...` |
| Empty charts | Wait 5+ min for data to flow through the full pipeline |

---

## Timing Summary

| Section | Duration | Cumulative |
|---------|----------|------------|
| Introduction | 5 min | 5 min |
| ML Models | 5 min | 10 min |
| Schema Governance (Apicurio Registry) | 5 min | 15 min |
| Live Demo: Start Pipeline | 10 min | 25 min |
| How It Works | 5 min | 30 min |
| Dashboard Walkthrough | 10 min | 40 min |
| Production & Wrap Up | 5 min | 45 min |
