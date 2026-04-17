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

Build images directly inside Minikube's Docker daemon to avoid stale image cache issues:

```bash
# Point docker CLI at Minikube's Docker daemon
eval $(minikube docker-env)

# Build all 3 images
docker build -t reddit-producer:local -f runtime/kafka/producer/Dockerfile runtime/kafka/producer/
docker build -t spark-inference:local -f runtime/spark/Dockerfile runtime/spark/
cd runtime/kafka/consumer/flair-consumer && mvn package -DskipTests -q && \
  docker build -t predictions-consumer:local -f src/main/docker/Dockerfile.jvm . && cd -
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

### Slide 0: Title

- "Hi everyone, I'm Carles Arnal, Principal Software Engineer at IBM. I work on Apicurio Registry, which is an open source schema and API registry."
- "Today I'm going to show you how to build a real-time content classification pipeline using only open source tools, running entirely on Kubernetes."

### Slide 1: The Problem

- "Real-time classification is everywhere. In finance, you need to detect fraud on transactions as they happen — you can't wait for a nightly batch job. In social media, content moderation and trend detection need to happen in seconds, not hours. In cybersecurity, threat classification has to be instantaneous."
- "The common pattern across all these use cases is the same: data arrives continuously, and you need to classify it in real-time, not in batch."

### Slide 2: Case Study

- "For this talk, I'm using a concrete case study: classifying Reddit posts from r/AskEurope into 13 flair categories — things like Travel, Politics, Food, Culture, and so on."
- "The entire stack is 100% open source. There's no proprietary component anywhere. You can deploy this on any Kubernetes cluster — Minikube, EKS, GKE, OpenShift — it doesn't matter."

### Slide 3: Architecture

- "Here's the architecture. We have pre-collected Reddit data that feeds into a Kafka producer. The producer validates messages against a JSON Schema stored in Apicurio Registry and publishes them to a Kafka topic called reddit-stream."
- "Spark Structured Streaming picks up those messages in micro-batches and runs two ML models in parallel — a Transformer and a scikit-learn classifier. Both predictions, along with confidence scores, go into a second topic called kafka-predictions."
- "Finally, a Quarkus consumer reads those predictions, computes real-time analytics, and serves six live dashboards."
- "The key here is that two ML models run in parallel: a fine-tuned DistilRoBERTa transformer and a Logistic Regression with TF-IDF and LSA. Comparing two models gives us richer insight — agreement rates, uncertainty zones, confusion matrices — all without needing ground-truth labels."
- "Apicurio Registry sits in the middle and enforces data contracts between components using JSON Schema. If someone changes the message format, the downstream components won't silently break."
- "Everything runs on Kubernetes using operators: Strimzi for Kafka, the Spark Operator for Spark, and standard Kubernetes deployments for the rest."

---

## Part 2 — The ML Models (5 min)

### Slide 4: Section divider

- "Let's talk about the two models we're running."

### Slide 5: Dual Model Approach

- "On the left, we have the Transformer model. It's a fine-tuned DistilRoBERTa — that's a distilled version of RoBERTa. It has 6 layers, 12 attention heads, and 768 hidden dimensions. We fine-tuned it on labeled r/AskEurope posts. It captures semantic context — it understands what a sentence *means*, not just what words appear in it."
- "On the right, we have the scikit-learn model. This is a much simpler pipeline: TF-IDF vectorization to convert text into numerical features, then Truncated SVD — also known as LSA, Latent Semantic Analysis — for dimensionality reduction, and finally Logistic Regression as the classifier. It captures term frequency patterns — it cares about *which words* appear and how often."
- "Both models output two things: a predicted flair category and a confidence score between 0 and 1."
- "So why run two models? Because the agreement rate between them acts as a proxy for prediction reliability. When both models agree, we can be more confident the prediction is correct. When they disagree, that's a flag that the post is ambiguous or unusual. And we get all of this without needing any ground-truth labels in production."

**Optionally show:**

- The training notebook: `model/model_training.ipynb`
- Model artifacts: `model/reddit_flair_classifier/` (DistilRoBERTa), `model/reddit_classifier.pkl` (Logistic Regression)

---

## Part 3 — Schema Governance with Apicurio Registry (5 min)

### Slide 6: Section divider

- "Before we get to the live demo, let's talk about schema governance — because in any data pipeline, the first thing that goes wrong is someone changing a message format without telling anyone."

### Slide 7: Apicurio Registry

- "This is where Apicurio Registry comes in. Before any data flows through the pipeline, we define contracts. We register JSON Schemas in Apicurio Registry for each Kafka topic."
- "We have two schemas. The first one, reddit-stream-value, defines the producer output: an id field and a content field with the cleaned text. The second one, kafka-predictions-value, defines the inference output: the id, plus the transformer's flair prediction and confidence, and the sklearn's flair prediction and confidence."
- "This prevents schema drift. If someone changes the producer to add a field, remove a field, or change a data type, the downstream consumers won't silently break — the schema validation will catch it."
- "Apicurio also supports compatibility rules — backward, forward, or full compatibility — so schemas can evolve safely over time. You can add optional fields without breaking existing consumers."

**What to show (in T1):**

```bash
# Show registered schemas
curl -s http://localhost:8081/apis/registry/v3/groups/reddit-realtime/artifacts | python3 -m json.tool
```

- "Let me show you the schemas that are registered. You can see both artifacts — reddit-stream-value and kafka-predictions-value — in the reddit-realtime group."
- Optionally open `schemas/reddit-stream-value.json` and `schemas/kafka-predictions-value.json` in the editor to show the schema definitions.

---

## Part 4 — Live Demo: Start the Pipeline (10 min)

### Slide 8: Section divider

- "Alright, time for the live demo. Let's start the pipeline and watch data flow in real-time."

### Slide 9: Pre-deployed state

**In T1:**

```bash
kubectl get pods -n reddit-realtime
kubectl get pods -n spark-operator
```

- "Let me show you what's already running. We have the Strimzi cluster operator managing our Kafka cluster. We have the Kafka broker itself — reddit-posts — running in KRaft mode, no ZooKeeper. We have Apicurio Registry for schema governance. And we have the Quarkus predictions consumer, which is our dashboard backend."
- "Over in the spark-operator namespace, we have the Spark operator and the inference driver with two executors. The Spark job has already loaded both ML models — the DistilRoBERTa transformer and the sklearn classifier — and it's sitting there waiting for data to arrive on the reddit-stream topic."
- "The only missing piece is the producer. Once we deploy it, data starts flowing through the entire pipeline."

### Start the producer

**In T1:**

```bash
kubectl apply -f runtime/kafka/producer/reddit_posts_processor.yaml
```

- "Let's deploy the producer. This is a Python pod that reads pre-collected Reddit posts from r/AskEurope — we have thousands of real posts stored as CSV files. It cleans each post — removes URLs, strips stopwords, does lemmatization — and then streams them to Kafka one by one with a small delay, simulating a real-time feed."
- "The producer uses the Confluent JSON Schema serializer, which validates every message against the schema stored in Apicurio Registry before sending it. If a message doesn't match the schema, it won't be sent."

**In T2, tail the logs:**

```bash
kubectl logs -n reddit-realtime -f deployment/kafka-producer
```

- "Let's tail the producer logs in the other terminal. You should see the cleaning pipeline running on each post and then confirmation messages showing the partition and offset where each message was sent."
- Wait for a few "Sent to reddit-stream partition X offset Y" lines to appear.

### Verify end-to-end flow

**In T1 (wait ~60 seconds for Spark to process a micro-batch):**

```bash
curl -s http://localhost:8080/flairs/statistics | python3 -m json.tool
```

- "Now let's check if predictions are flowing through the entire pipeline. I'll curl the statistics endpoint on the Quarkus consumer."
- "And we can see data — per-flair counts, average confidence scores for both models, and agreement rates. The pipeline is working end-to-end: data went from CSV files, through Kafka, through Spark's dual-model inference, and into the Quarkus consumer — all in real-time."

---

## Part 5 — How It Works (5 min)

### Slide 11: Section divider

- "While data keeps flowing in the background, let me explain what's happening at each stage of the pipeline. If you want to see the code, the entire repo is on GitHub — I'll share the link at the end."

### Slide 12: Data Journey

- "Stage 1: Collect and clean. The producer reads pre-collected Reddit posts from CSV files. Each post goes through a cleaning pipeline — we strip URLs, remove stopwords, remove punctuation, lemmatize the text, and then concatenate the title, body, comments, and domain into a single content field. This gives the models a clean, unified input."
- "Stage 2: Validate and stream. Before the message goes to Kafka, it's validated against the JSON Schema stored in Apicurio Registry using the Confluent JSON Schema serializer. Only valid messages make it to the reddit-stream topic."
- "Stage 3: Dual inference. Spark Structured Streaming picks up messages in micro-batches. For each post, it runs two models in parallel inside a PySpark UDF. The transformer tokenizes the text, runs a forward pass through the neural network, and applies softmax to get probabilities. The sklearn model runs TF-IDF, then LSA for dimensionality reduction, then classifies with Logistic Regression. Both outputs — predicted flair plus confidence score — go into the kafka-predictions topic."
- "Stage 4: Consume and analyze. The Quarkus consumer reads from kafka-predictions using SmallRye Reactive Messaging. It validates each message against the predictions schema from Apicurio Registry. Then it computes real-time statistics — agreement rates, confusion matrix, confidence distributions, uncertainty zones — all in-memory, and serves them via REST endpoints that power the dashboards."

### Slide 13: Design Decisions

- "Let me highlight four design decisions that are worth discussing."
- "First, inference inside Spark. We run the models as PySpark UDFs rather than calling an external model server. This avoids network latency for each prediction and keeps the pipeline simple. The trade-off is that inference is coupled to the streaming framework."
- "Second, in-memory analytics. The consumer keeps all statistics in Java ConcurrentHashMaps. It's fast and has zero dependencies, but state is lost on restart. In production, you'd persist to a time-series database like InfluxDB or TimescaleDB."
- "Third, schema-first contracts. We register JSON Schemas in Apicurio Registry before any data flows. This means each component — the producer, Spark, and the consumer — can evolve independently as long as the schemas remain compatible."
- "Fourth, lazy model loading. The Transformer and sklearn models are loaded on first use, not at import time. This is critical because PySpark tries to pickle UDF closures and serialize them to executors — and you can't pickle a neural network. By loading lazily inside the UDF function, the models only exist on the executors, never in the driver's serialization path."

---

## Part 6 — Dashboard Walkthrough (10 min)

### Slide 14: Section divider

- "Now let's switch to the browser and look at the dashboards. They auto-refresh, so you'll see the data updating live as the pipeline continues to process posts."

### Slide 15: Dashboard overview

- "We have six dashboards, each showing a different angle on the pipeline. Let me walk through each one."

### Main Metrics (`/metrics.html`) — switch to browser tab 1

- "This is the operational overview — the first thing you'd look at to understand how the pipeline is performing."
- "The first chart shows flair distribution — how many posts were classified into each category. You can see which categories are most common in r/AskEurope."
- "The second chart shows average confidence for each flair. Higher confidence means the models are more sure about their predictions for that category."
- "The third chart compares the two models side by side — transformer confidence in blue, sklearn in purple. You can immediately see where they diverge."
- "The fourth chart is the agreement rate per flair. Notice how some flairs like Meta and Food have near-perfect agreement — both models find them easy. Others like Work and Sports are harder for both."
- "The fifth chart shows the confidence gap — the average difference between the two models' confidence scores. A large gap means one model is confident and the other is guessing."

### Confusion Matrix (`/confusion-matrix.html`) — switch to browser tab 2

- "This is a D3.js heatmap showing the confusion matrix between the two models. The transformer's predictions are on the X axis, sklearn's on the Y axis."
- "The diagonal shows agreement — both models predicted the same flair. The brighter the diagonal cell, the more often they agreed on that category."
- "Off-diagonal cells reveal systematic differences. For example, if you see a bright cell where the transformer says Travel but sklearn says Culture, that means the sklearn model consistently confuses those two categories."
- "This tells you *how* the models disagree, not just *how often* — which is critical for understanding their failure modes."

### Confidence Distribution (`/confidence-distribution.html`) — switch to browser tab 3

- "This is a stacked histogram comparing the confidence distributions of both models."
- "Look at the transformer — it tends to be very polarized. Most of its predictions cluster in the highest confidence band, 0.9 to 1.0. It commits strongly to its choices. This is typical of neural networks with softmax outputs — the softmax function tends to push probability mass toward one class."
- "Now look at the sklearn model — its confidence is spread much more evenly across the range. It hesitates more, especially on ambiguous posts. The logistic regression outputs calibrated probabilities that are more spread out."
- "This is a fundamental difference between deep learning and linear models. The transformer gives you sharp, decisive predictions. The sklearn model gives you more nuanced, hedging predictions. Having both gives you a fuller picture."

### Model Uncertainty (`/model-uncertainty.html`) — switch to browser tab 4

- "This is the most actionable chart in the entire pipeline. It's a doughnut chart with three zones."
- "The green zone, Both Confident, means both models had confidence above 0.6. These are reliable predictions — you can trust them and act on them automatically."
- "The orange zone, Both Uncertain, means both models had confidence below 0.6. These are genuinely hard examples — ambiguous text, posts that could belong to multiple categories, edge cases."
- "The red zone, Disagreement, is the most interesting. One model is confident and the other is not. These are the best candidates for manual review or active learning — they're the posts where you'd get the most value from a human label."
- "And here's the key insight of the whole pipeline: you get all of this — reliability assessment, uncertainty detection, review candidates — without any ground-truth labels in production. The models supervise each other."

### Agreement Over Time (`/agreement-over-time.html`) — switch to browser tab 5

- "This line chart shows the agreement rate between the two models over time."
- "If this line is stable, your models are performing consistently — the incoming data looks similar to what they were trained on."
- "If you see agreement dropping over time, that's a strong signal of data drift. It means the incoming data is shifting away from the training distribution, and the models are starting to disagree more."
- "In production, you'd set an alert on this metric. A sustained drop below a threshold should trigger a retraining cycle."

### Flair Drift (`/flair-drift.html`) — switch to browser tab 6

- "This is a multi-line chart with one line per flair category, showing daily frequency."
- "This helps you detect data drift at the category level. If a specific flair suddenly spikes or drops, something changed in the incoming data."
- "For example, a political event could cause a spike in Politics posts. A seasonal trend could shift Travel or Food patterns. If the distribution shifts significantly from what the models were trained on, it's time to retrain."
- "This chart combined with the agreement trend gives you a complete data drift monitoring system — and it's all computed in real-time by the Quarkus consumer."

---

## Part 7 — Conclusions (5 min)

### Slide 16: Key Insight — Uncertainty zones

- "Before we wrap up, I want to highlight the most important takeaway from the dashboards."
- "The uncertainty zone chart divides every prediction into three buckets. Both Confident is your green zone — reliable, automated. Both Uncertain is your orange zone — these are hard for everyone. But the Disagreement zone, in red, is the gold mine."
- "When one model is confident and the other is not, that's the strongest signal that something interesting is happening with that post. Maybe it's an edge case between two categories. Maybe one model learned a pattern the other didn't."
- "These disagreement cases are exactly the posts you should send for manual review or use for active learning to improve both models. And you identify them without any ground-truth labels — the models supervise each other."

### Slide 17: What the pipeline revealed

- "So what did we actually learn from running this pipeline on live data? Four things."
- "First, the transformer is highly confident. The neural network concentrates almost all its predictions in the top confidence band. When it makes a prediction, it commits fully. This is typical of deep learning models with softmax outputs — the softmax function tends to push probability mass toward one class."
- "Second, the sklearn model is more cautious and distributed. The linear model spreads its confidence across a much wider range. It hesitates more, especially on ambiguous posts. This isn't a weakness — it's a fundamentally different confidence profile. Different model families behave differently, and that difference is exactly what makes dual-model inference valuable."
- "Third, agreement depends heavily on the category. Some flairs — like Food, Meta, and Politics — show strong agreement. Both models find them easy to classify. Others — like Work, Sports, and Personal — are consistently harder. This tells us where our training data or feature engineering may need improvement."
- "Fourth, and most importantly, disagreement is the most valuable signal. Posts where one model is confident and the other is not are the best candidates for manual review and active learning. You can identify your most informative examples — the ones where a human label would help the most — entirely automatically, without any ground-truth labels in production."

---

## Part 8 — Production Considerations & Wrap Up (5 min)

### Slide 18: Section divider

- "This was a demo, so let's talk about what you'd add to take this to production."

### Slide 19: Production hardening

- "Observability. In production, you'd add Prometheus and Grafana for metrics — things like message throughput, inference latency, consumer lag. And Jaeger for distributed tracing so you can follow a single message through the entire pipeline. Quarkus has built-in support for all three — it's just a matter of adding the extensions."
- "Failure handling. The consumer already uses SmallRye's Dead Letter Queue — if a message fails to process, it goes to a DLQ topic instead of blocking the pipeline. In production, you'd add DLQs at every stage. You'd also add exponential backoff with jitter for retries, and circuit breaker patterns to handle downstream failures gracefully."
- "Scaling. Kafka topics can be partitioned for parallel processing — we used 3 partitions in this demo, but you could use dozens. Spark executors scale horizontally — add more executors to process more micro-batches in parallel. The Quarkus consumer can be replicated behind a load balancer."
- "Schema evolution. Apicurio Registry supports backward and forward compatibility rules. This means you can safely add optional fields to a schema without breaking existing consumers. You can enforce that every schema change is backward-compatible before it's allowed."

### Slide 20: Extensibility

- "One thing I want to emphasize is that the pipeline pattern is completely reusable. Reddit is just the data source we used for this demo."
- "You can swap the source — the same pipeline works with Twitter, IoT sensors, application logs, internal databases, webhooks. Any event source that can produce messages to Kafka."
- "You can swap the models — DistilRoBERTa could be replaced with BERT, GPT, or any HuggingFace model. The sklearn model could be swapped for XGBoost, Random Forest, or any classifier."
- "You can swap the target — instead of a dashboard, predictions could feed alerting systems, write to a database, or trigger downstream services."
- "And everything in this stack is open source. Strimzi, Spark Operator, Quarkus, Kafka, Apicurio Registry, PyTorch, scikit-learn — no vendor lock-in. You own the entire pipeline."

### Slide 21: Key Takeaways

- "Let me wrap up with four key takeaways."
- "One: building real-time ML pipelines with open source is achievable and practical. You don't need expensive proprietary tools. Everything you saw today is free and open source."
- "Two: running dual models provides richer observability than a single model. Each model family exposes the other's blind spots. You get agreement rates, uncertainty zones, and confusion matrices — all without ground-truth labels."
- "Three: schema governance with Apicurio Registry prevents silent contract violations. As your pipeline evolves — and it will — the schemas ensure that changes don't break downstream components."
- "Four: Kubernetes operators like Strimzi and the Spark Operator turn complex distributed systems into declarative YAML. You describe what you want, and the operator handles the rest."

### Slide 22: Thank You

- "Thank you. The entire code is on GitHub — I'll share the link. Happy to take any questions."

---

## Troubleshooting Quick Reference

| Problem | Fix |
|---------|-----|
| Pods stuck in Pending | `kubectl describe pod <name> -n reddit-realtime` |
| No data in dashboard | Check producer logs: `kubectl logs -f deployment/kafka-producer -n reddit-realtime` |
| Spark job failing | Check driver: `kubectl logs spark-reddit-inference-driver -n spark-operator` |
| Port-forward dropped | Re-run: `kubectl port-forward -n reddit-realtime svc/predictions-consumer 8080:80` |
| Apicurio schemas lost | In-memory storage; re-register: `REGISTRY_URL=http://localhost:8081 schemas/register-schemas.sh` |
| Producer CrashLoopBackOff | Check logs: `kubectl logs -f deployment/kafka-producer -n reddit-realtime` |
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
| Conclusions | 5 min | 45 min |
| Production & Wrap Up | 5 min | 50 min |
