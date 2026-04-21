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
kubectl wait --for=condition=available deployment/apicurio-registry-ui -n reddit-realtime --timeout=120s
kubectl wait --for=condition=available deployment/predictions-consumer -n reddit-realtime --timeout=120s

# Set up port-forwarding
kubectl port-forward -n reddit-realtime svc/predictions-consumer 8080:80 &
kubectl port-forward -n reddit-realtime svc/apicurio-registry 8081:8080 &
kubectl port-forward -n reddit-realtime svc/apicurio-registry-ui 8083:8080 &
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

### Start embedded terminals

The presentation embeds live terminals via ttyd. Start two instances before the talk:

```bash
# T1 — kubectl commands, deploy, curl (-W enables typing)
ttyd -W -p 7681 bash &

# T2 — producer logs
ttyd -W -p 7682 bash &
```

Verify they work by opening `http://localhost:7681` and `http://localhost:7682` in the browser.

**Serve the presentation via HTTP** (required for embedded terminals to load):

```bash
# From the project root
python3 -m http.server 8082 &
```

Open `http://localhost:8082/presentation.html` in the browser (do NOT open the file directly — the ttyd iframes won't load from a `file://` URL).

**Browser tabs (pre-load):**

1. `http://localhost:8080/metrics.html` — Main dashboard
2. `http://localhost:8080/confusion-matrix.html` — Model comparison heatmap
3. `http://localhost:8080/confidence-distribution.html` — Confidence histograms
4. `http://localhost:8080/model-uncertainty.html` — Uncertainty zones
5. `http://localhost:8083` — Apicurio Registry UI

---

## Part 1 — Introduction (5 min)

### Slide 0: Title

- "Hi everyone, I'm Carles Arnal, Principal Software Engineer at IBM. I work on Apicurio Registry, which is an open source schema and API registry. I've been contributing to open source for over a decade, and today I want to show you a project that combines several of my favorite open source tools."
- "Today I'm going to show you how to build a real-time content classification pipeline using only open source tools, running entirely on Kubernetes. We'll go from raw text data to live dashboards — and I'll deploy the whole thing live on stage so you can see it working end-to-end."

### Slide 1: The Problem

- "Real-time classification is everywhere. In finance, you need to detect fraud on transactions as they happen — you can't wait for a nightly batch job. In social media, content moderation and trend detection need to happen in seconds, not hours. In cybersecurity, threat classification has to be instantaneous."
- "The common pattern across all these use cases is the same: data arrives continuously, and you need to classify it in real-time, not in batch."
- "And the challenge isn't just running a model — it's building a reliable pipeline around it. You need data ingestion, schema contracts, inference, analytics, and monitoring. Each of those is a distributed systems problem on its own. Today I'll show you how to wire them all together with open source."

### Slide 2: Case Study

- "For this talk, I'm using a concrete case study: classifying Reddit posts from r/AskEurope into 13 flair categories — things like Travel, Politics, Food, Culture, and so on."
- "Why Reddit? Because it's messy, real-world text data. Posts are informal, they mix languages, they have slang and abbreviations. It's exactly the kind of noisy input that makes classification hard — and that's what makes it interesting."
- "The entire stack is 100% open source. There's no proprietary component anywhere. You can deploy this on any Kubernetes cluster — Minikube, EKS, GKE, OpenShift — it doesn't matter."

### Slide 3: Architecture

- "Here's the architecture. We have pre-collected Reddit data that feeds into a Kafka producer. The producer validates messages against a JSON Schema stored in Apicurio Registry and publishes them to a Kafka topic called reddit-stream."
- "Spark Structured Streaming picks up those messages in micro-batches and runs two ML models in parallel — a Transformer and a scikit-learn classifier. Both predictions, along with confidence scores, go into a second topic called kafka-predictions."
- "Finally, a Quarkus consumer reads those predictions, computes real-time analytics, and serves four live dashboards."
- "The key here is that two ML models run in parallel: a fine-tuned DistilRoBERTa transformer and a Logistic Regression with TF-IDF and LSA. Comparing two models gives us richer insight — agreement rates, uncertainty zones, confusion matrices — all without needing ground-truth labels."
- "Apicurio Registry sits in the middle and enforces data contracts between components using JSON Schema. If someone changes the message format, the downstream components won't silently break."
- "Everything runs on Kubernetes using operators: Strimzi for Kafka, the Spark Operator for Spark, and standard Kubernetes deployments for the rest."

---

## Part 2 — The ML Models (5 min)

### Slide 4: Section divider

- "Let's talk about the two models we're running. This is the core of the pipeline — everything else is plumbing to get data to the models and insights out of them."

### Slide 5: Dual Model Approach

- "On the left, we have the Transformer model. It's a fine-tuned DistilRoBERTa — that's a distilled version of RoBERTa with about 82 million parameters. It has 6 layers, 12 attention heads, and 768 hidden dimensions. We fine-tuned it on labeled r/AskEurope posts. It captures semantic context — it understands what a sentence *means*, not just what words appear in it."
- "Why DistilRoBERTa and not full RoBERTa or BERT? Because we need to run this in real-time inside Spark. DistilRoBERTa is 40% smaller and 60% faster than RoBERTa, with only a small drop in accuracy. For a streaming pipeline where latency matters, that trade-off is worth it."
- "For example, a post that says 'I visited my grandmother in Krakow and she made pierogi' — the transformer understands that's about Food *and* Travel, and it can weigh the context to decide which flair fits best. A bag-of-words model would see 'Krakow' and 'pierogi' as independent signals. The transformer sees the *story*."
- "On the right, we have the scikit-learn model. This is a much simpler pipeline: TF-IDF vectorization to convert text into numerical features, then Truncated SVD — also known as LSA, Latent Semantic Analysis — for dimensionality reduction, and finally Logistic Regression as the classifier. It captures term frequency patterns — it cares about *which words* appear and how often."
- "The sklearn model is fast and lightweight — inference takes microseconds compared to milliseconds for the transformer. It also produces well-calibrated probabilities, which means its confidence scores are more spread out and nuanced. When sklearn says 0.7, it really means 0.7. When the transformer says 0.95, it might mean anything from 'very sure' to 'I always say 0.95'."
- "Both models were trained on the same labeled dataset — about 30,000 r/AskEurope posts that already had flair labels assigned by the Reddit community. We collected the data from the subreddit, cleaned it — removed URLs, stopwords, punctuation, applied lemmatization — and then split the data 80/20 for training and validation."
- "The transformer achieved around 65% accuracy, and the sklearn model around 55%. Those numbers aren't spectacular, but remember — 13 categories with noisy, informal text is genuinely hard. A random baseline would give you about 7.7%. And some of these categories are inherently ambiguous — is a post about Italian food in a travel context classified as Food or Travel? Even humans would disagree."
- "Both models output two things: a predicted flair category and a confidence score between 0 and 1."
- "So why run two models? Because the agreement rate between them acts as a proxy for prediction reliability. When both models agree, we can be more confident the prediction is correct. When they disagree, that's a flag that the post is ambiguous or unusual. And we get all of this without needing any ground-truth labels in production."
- "This idea — using model agreement as a supervision signal — is related to ensemble methods and co-training in the ML literature. But here we're not ensembling to improve accuracy. We're using disagreement as an *observability tool*. That's the key insight. The goal isn't to get 100% accuracy — it's to know *where* we're likely to be wrong."

**Optionally show:**

- The training notebook: `model/model_training.ipynb`
- Model artifacts: `model/reddit_flair_classifier/` (DistilRoBERTa), `model/reddit_classifier.pkl` (Logistic Regression)

---

## Part 3 — Schema Governance with Apicurio Registry (5 min)

### Slide 6: Section divider

- "Before we get to the live demo, let's talk about schema governance — because in any data pipeline, the first thing that goes wrong is someone changing a message format without telling anyone."

### Slide 7: Apicurio Registry

- "This is where Apicurio Registry comes in. Before any data flows through the pipeline, we define contracts. We register JSON Schemas in Apicurio Registry for each Kafka topic."
- "If you've worked with microservices, you know this pattern — it's the same idea as an OpenAPI spec for a REST API, but for event-driven systems. The schema is the contract between the producer and the consumer. If someone breaks the contract, you want to know *before* data flows, not after."
- "We have two schemas. The first one, reddit-stream-value, defines the producer output: an id field and a content field with the cleaned text. The second one, kafka-predictions-value, defines the inference output: the id, plus the transformer's flair prediction and confidence, and the sklearn's flair prediction and confidence."
- "This prevents schema drift. If someone changes the producer to add a field, remove a field, or change a data type, the downstream consumers won't silently break — the schema validation will catch it. In our pipeline, the Confluent JSON Schema serializer validates every message *at the producer* before it even hits Kafka. And the Quarkus consumer validates again on the other side. Belt and suspenders."
- "Apicurio also supports compatibility rules — backward, forward, or full compatibility — so schemas can evolve safely over time. You can add optional fields without breaking existing consumers. This is critical in production where you can't update all components at the same time — you need to be able to deploy the producer and consumer independently."
- "Apicurio Registry supports not just JSON Schema but also Avro, Protobuf, OpenAPI, AsyncAPI, and more. For this demo we chose JSON Schema because the messages are JSON and the schemas are human-readable — easy to inspect and understand."

**What to show (in T1):**

```bash
# Show registered schemas
curl -s http://localhost:8081/apis/registry/v3/groups/reddit-realtime/artifacts | python3 -m json.tool
```

- "Let me show you the schemas that are registered. You can see both artifacts — reddit-stream-value and kafka-predictions-value — in the reddit-realtime group."
- Click the **"Open Apicurio Registry UI"** link on the slide (or switch to browser tab 5) to show the schemas in the Registry UI.
- "And here's the Apicurio Registry UI — you can browse the registered artifacts, see their versions, and inspect the schema definitions visually. You can click into any artifact to see the full schema, its version history, and its compatibility rules."
- Optionally open `schemas/reddit-stream-value.json` and `schemas/kafka-predictions-value.json` in the editor to show the schema definitions.

---

## Part 4 — Live Demo: Start the Pipeline (10 min)

### Slide 8: Section divider

- "Alright, time for the live demo. Let's start the pipeline and watch data flow in real-time."

### Slide 9: Pre-deployed state (embedded terminal)

The terminal is embedded in the slide. Type directly in it.

**Type in the embedded terminal:**

```bash
kubectl get pods -n reddit-realtime && kubectl get pods -n spark-operator
```

- "Let me show you what's already running. I can type right here in the presentation — this is a live terminal embedded in the slides."
- "We have the Strimzi cluster operator managing our Kafka cluster. Strimzi is a CNCF project that brings Kafka to Kubernetes — you describe your cluster in YAML and the operator handles the rest: brokers, topics, authentication, rolling upgrades."
- "We have the Kafka broker itself — reddit-posts — running in KRaft mode, which means no ZooKeeper. This is Kafka 4.1, where ZooKeeper has been fully removed. The cluster is lighter and simpler to operate."
- "We have Apicurio Registry for schema governance — the schemas we just talked about. And we have the Quarkus predictions consumer, which is our dashboard backend. It's already listening on the kafka-predictions topic, but there's no data yet."
- "Over in the spark-operator namespace, we have the Spark operator and the inference driver with two executors. The Spark job has already loaded both ML models — the DistilRoBERTa transformer and the sklearn classifier — and it's sitting there waiting for data to arrive on the reddit-stream topic. Loading the transformer model takes about 30 seconds, which is why we pre-deployed it."
- "The only missing piece is the producer. Once we deploy it, data starts flowing through the entire pipeline."

### Slide 10: Start producer (two embedded terminals)

This slide has two side-by-side terminals embedded in it.

**Type in T1 (left terminal):**

```bash
kubectl apply -f runtime/kafka/producer/reddit_posts_processor.yaml
```

- "Let's deploy the producer. This is a Python pod that reads pre-collected Reddit posts from r/AskEurope — we have thousands of real posts stored as CSV files. It cleans each post — removes URLs, strips stopwords, does lemmatization — and then streams them to Kafka one by one with a small delay, simulating a real-time feed."
- "The producer uses the Confluent JSON Schema serializer, which validates every message against the schema stored in Apicurio Registry before sending it. If a message doesn't match the schema, it won't be sent."

**Type in T2 (right terminal):**

```bash
kubectl logs -n reddit-realtime -f deployment/kafka-producer
```

- "In the right terminal, let's tail the producer logs. You should see the cleaning pipeline running on each post and then confirmation messages showing the partition and offset where each message was sent."
- Wait for a few "Sent to reddit-stream partition X offset Y" lines to appear.

**Back in T1 (wait ~60 seconds for Spark to process a micro-batch):**

```bash
curl -s http://localhost:8080/flairs/statistics | python3 -m json.tool
```

- "Now let's check if predictions are flowing through the entire pipeline. I'll curl the statistics endpoint on the Quarkus consumer. This might take a minute — Spark processes data in micro-batches, so there's a small delay before the first batch is processed and written to kafka-predictions."
- "And we can see data — per-flair counts, average confidence scores for both models, and agreement rates. The pipeline is working end-to-end: data went from CSV files, through the producer's cleaning pipeline, validated against the schema in Apicurio Registry, published to Kafka, picked up by Spark, run through both ML models, predictions published to a second Kafka topic, consumed by the Quarkus app, and aggregated into real-time statistics. All of that happened automatically."

---

## Part 5 — How It Works (5 min)

### Slide 11: Section divider

- "While data keeps flowing in the background — and by the way, every post you see in those logs is going through the full pipeline right now — let me explain what's happening at each stage. If you want to see the code, the entire repo is on GitHub — I'll share the link at the end."

### Slide 12: Data Journey

- "Stage 1: Collect and clean. The producer reads pre-collected Reddit posts from CSV files. Each post goes through a cleaning pipeline — we strip URLs, remove stopwords, remove punctuation, lemmatize the text, and then concatenate the title, body, comments, and domain into a single content field. This gives the models a clean, unified input."
- "Stage 2: Validate and stream. Before the message goes to Kafka, it's validated against the JSON Schema stored in Apicurio Registry using the Confluent JSON Schema serializer. Only valid messages make it to the reddit-stream topic."
- "Stage 3: Dual inference. Spark Structured Streaming picks up messages in micro-batches. For each post, it runs two models in parallel inside a PySpark UDF. The transformer tokenizes the text, runs a forward pass through the neural network, and applies softmax to get probabilities. The sklearn model runs TF-IDF, then LSA for dimensionality reduction, then classifies with Logistic Regression. Both outputs — predicted flair plus confidence score — go into the kafka-predictions topic."
- "Stage 4: Consume and analyze. The Quarkus consumer reads from kafka-predictions using SmallRye Reactive Messaging. It validates each message against the predictions schema from Apicurio Registry. Then it computes real-time statistics — agreement rates, confusion matrix, confidence distributions, uncertainty zones — all in-memory, and serves them via REST endpoints that power the dashboards."

### Slide 13: Design Decisions

- "Let me highlight four design decisions that are worth discussing. These are the kinds of trade-offs you'd face in any real-time ML pipeline."
- "First, inference inside Spark. We run the models as PySpark UDFs rather than calling an external model server like TensorFlow Serving or Triton. This avoids network latency for each prediction and keeps the pipeline simple — there's no separate service to deploy, scale, and monitor. The trade-off is that inference is coupled to the streaming framework. If you need to update a model, you have to rebuild the Spark image. In a larger system you might prefer a dedicated model server, but for this pipeline the simplicity wins."
- "Second, in-memory analytics. The consumer keeps all statistics in Java ConcurrentHashMaps. It's fast and has zero dependencies, but state is lost on restart. In production, you'd persist to a time-series database like InfluxDB or TimescaleDB. For this demo, in-memory is perfect — it lets us focus on the pipeline logic without adding more infrastructure."
- "Third, schema-first contracts. We register JSON Schemas in Apicurio Registry before any data flows. This means each component — the producer, Spark, and the consumer — can evolve independently as long as the schemas remain compatible. In practice, this is huge. It means the team working on the producer doesn't need to coordinate with the team working on the consumer every time they make a change."
- "Fourth, lazy model loading. The Transformer and sklearn models are loaded on first use, not at import time. This is critical because PySpark tries to pickle UDF closures and serialize them to executors — and you can't pickle a neural network. If you define the model at module level, PySpark will try to serialize 82 million parameters and fail. By loading lazily inside the UDF function, the models only exist on the executors, never in the driver's serialization path. This one took a while to debug."

---

## Part 6 — Dashboard Walkthrough (10 min)

### Slide 14: Section divider

- "Now let's switch to the browser and look at the dashboards. They auto-refresh, so you'll see the data updating live as the pipeline continues to process posts."

### Slide 15: Dashboard overview

- "We have four dashboards, each showing a different angle on the pipeline. You can click any card to open it. All of them are served by the Quarkus consumer — it computes everything in real-time as predictions flow in. Let me walk through each one."

### Main Metrics (`/metrics.html`) — switch to browser tab 1

- "This is the operational overview — the first thing you'd look at to understand how the pipeline is performing. It's the dashboard you'd put on a monitor in a war room."
- "The first chart shows flair distribution — how many posts were classified into each category. You can see which categories are most common in r/AskEurope."
- "The second chart shows average confidence for each flair. Higher confidence means the models are more sure about their predictions for that category."
- "The third chart compares the two models side by side — transformer confidence in blue, sklearn in purple. You can immediately see where they diverge."
- "The fourth chart is the agreement rate per flair. Notice how some flairs like Meta and Food have near-perfect agreement — both models find them easy. Others like Work and Sports are harder for both."
- "The fifth chart shows the confidence gap — the average difference between the two models' confidence scores. A large gap means one model is confident and the other is guessing."

### Confusion Matrix (`/confusion-matrix.html`) — switch to browser tab 2

- "This is a D3.js heatmap showing the confusion matrix between the two models. The transformer's predictions are on the X axis, sklearn's on the Y axis."
- "The diagonal shows agreement — both models predicted the same flair. The brighter the diagonal cell, the more often they agreed on that category."
- "Off-diagonal cells reveal systematic differences. For example, if you see a bright cell where the transformer says Travel but sklearn says Culture, that means the sklearn model consistently confuses those two categories."
- "This tells you *how* the models disagree, not just *how often* — which is critical for understanding their failure modes. If you were building an active learning system, these off-diagonal cells tell you exactly which category pairs to focus your labeling effort on."

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


---

## Part 7 — Production Considerations (5 min)

### Slide 16: Key Insight — Uncertainty zones

- "Before we move on, I want to highlight the most important takeaway from the dashboards."
- "The uncertainty zone chart divides every prediction into three buckets. Both Confident is your green zone — reliable, automated. Both Uncertain is your orange zone — these are hard for everyone. But the Disagreement zone, in red, is the gold mine."
- "When one model is confident and the other is not, that's the strongest signal that something interesting is happening with that post. Maybe it's an edge case between two categories. Maybe one model learned a pattern the other didn't."
- "These disagreement cases are exactly the posts you should send for manual review or use for active learning to improve both models. And you identify them without any ground-truth labels — the models supervise each other."

### Slide 17: Section divider

- "This was a demo, so let's talk about what you'd add to take this to production."

### Slide 18: Production hardening

- "Observability. In production, you'd add Prometheus and Grafana for metrics — things like message throughput, inference latency, consumer lag. And Jaeger for distributed tracing so you can follow a single message through the entire pipeline. Quarkus has built-in support for all three — it's just a matter of adding the extensions."
- "Failure handling. The consumer already uses SmallRye's Dead Letter Queue — if a message fails to process, it goes to a DLQ topic instead of blocking the pipeline. In production, you'd add DLQs at every stage. You'd also add exponential backoff with jitter for retries, and circuit breaker patterns to handle downstream failures gracefully."
- "Scaling. Kafka topics can be partitioned for parallel processing — we used a single partition in this demo for simplicity, but you could use dozens. Spark executors scale horizontally — add more executors to process more micro-batches in parallel. The Quarkus consumer can be replicated behind a load balancer."
- "Schema evolution. Apicurio Registry supports backward and forward compatibility rules. This means you can safely add optional fields to a schema without breaking existing consumers. You can enforce that every schema change is backward-compatible before it's allowed."

### Slide 19: Extensibility

- "One thing I want to emphasize is that the pipeline pattern is completely reusable. Reddit is just the data source we used for this demo. The architecture itself is a general-purpose real-time classification pipeline."
- "You can swap the source — the same pipeline works with Twitter, IoT sensors, application logs, internal databases, webhooks. Any event source that can produce messages to Kafka. In fact, you could run multiple producers for different sources in parallel, all feeding into the same pipeline."
- "You can swap the models — DistilRoBERTa could be replaced with BERT, GPT, or any HuggingFace model. The sklearn model could be swapped for XGBoost, Random Forest, or any classifier. You could even swap in an LLM for zero-shot classification and compare it against a fine-tuned model — the dual-model pattern still works."
- "You can swap the target — instead of a dashboard, predictions could feed alerting systems, write to a database, or trigger downstream services. You could have a Kafka consumer that sends a Slack alert every time the models disagree on a high-priority category."
- "And everything in this stack is open source. Strimzi, Spark Operator, Quarkus, Kafka, Apicurio Registry, PyTorch, scikit-learn — no vendor lock-in. You own the entire pipeline. You can fork the repo, swap the pieces, and have your own real-time classification system running by the end of the week."

---

## Part 8 — Conclusions & Wrap Up (5 min)

### Slide 20: What the pipeline revealed

- "So what did we actually learn from running this pipeline on live data? Four things — and these aren't theoretical, they're observations from the dashboards we just looked at."
- "First, the transformer is highly confident. The neural network concentrates almost all its predictions in the top confidence band. When it makes a prediction, it commits fully. This is typical of deep learning models with softmax outputs — the softmax function tends to push probability mass toward one class. If you only had the transformer, you might think every prediction is reliable. But comparing it to the sklearn model tells a different story."
- "Second, the sklearn model is more cautious and distributed. The linear model spreads its confidence across a much wider range. It hesitates more, especially on ambiguous posts. This isn't a weakness — it's a fundamentally different confidence profile. Different model families behave differently, and that difference is exactly what makes dual-model inference valuable. You learn things from the comparison that neither model can tell you alone."
- "Third, agreement depends heavily on the category. Some flairs — like Food, Meta, and Politics — show strong agreement. Both models find them easy to classify. Others — like Work, Sports, and Personal — are consistently harder. This tells us where our training data or feature engineering may need improvement. It's a roadmap for where to invest your labeling budget."
- "Fourth, and most importantly, disagreement is the most valuable signal. Posts where one model is confident and the other is not are the best candidates for manual review and active learning. You can identify your most informative examples — the ones where a human label would help the most — entirely automatically, without any ground-truth labels in production. This is the single biggest takeaway: two cheap models supervising each other can be more useful than one expensive model running alone."

### Slide 21: Key Takeaways

- "Let me wrap up with four key takeaways."
- "One: building real-time ML pipelines with open source is achievable and practical. You don't need expensive proprietary tools. Everything you saw today is free and open source."
- "Two: running dual models provides richer observability than a single model. Each model family exposes the other's blind spots. You get agreement rates, uncertainty zones, and confusion matrices — all without ground-truth labels."
- "Three: schema governance with Apicurio Registry prevents silent contract violations. As your pipeline evolves — and it will — the schemas ensure that changes don't break downstream components."
- "Four: Kubernetes operators like Strimzi and the Spark Operator turn complex distributed systems into declarative YAML. You describe what you want, and the operator handles the rest."

### Slide 22: Thank You

- "Thank you. The entire code is on GitHub — the link is right here on the slide. Everything you saw today — the pipeline, the dashboards, the Kubernetes manifests, the ML models, even this presentation — it's all in the repo. Feel free to fork it, adapt it, and build your own real-time classification pipeline."
- "Happy to take any questions."

---

## Troubleshooting Quick Reference

| Problem | Fix |
|---------|-----|
| Pods stuck in Pending | `kubectl describe pod <name> -n reddit-realtime` |
| No data in dashboard | Check producer logs: `kubectl logs -f deployment/kafka-producer -n reddit-realtime` |
| Spark job failing | Check driver: `kubectl logs spark-reddit-inference-driver -n spark-operator` |
| Port-forward dropped | Re-run: `kubectl port-forward -n reddit-realtime svc/predictions-consumer 8080:80` |
| Registry UI port-forward dropped | Re-run: `kubectl port-forward -n reddit-realtime svc/apicurio-registry-ui 8083:8080` |
| Registry UI can't connect | CORS issue; restart backend: `kubectl rollout restart deployment/apicurio-registry -n reddit-realtime` then re-run port-forwards |
| Apicurio schemas lost | In-memory storage; re-register: `REGISTRY_URL=http://localhost:8081 schemas/register-schemas.sh` |
| Producer CrashLoopBackOff | Check logs: `kubectl logs -f deployment/kafka-producer -n reddit-realtime` |
| Empty charts | Wait 5+ min for data to flow through the full pipeline |

---

## Potential Q&A Questions

### ML / Models

**"Why not use a single, more accurate model?"**
- The point isn't maximizing accuracy — it's maximizing *observability*. A single model gives you a prediction and a confidence score, but you have no way to assess reliability without ground-truth labels. Two models give you agreement rates, uncertainty zones, and confusion matrices for free. You learn *where* the system is likely to be wrong, not just *what* it predicts.

**"What accuracy do the models achieve?"**
- The transformer gets around 65% and the sklearn model around 55%. That's not state-of-the-art, but it's a 13-class problem with noisy, informal text. A random baseline would give 7.7%. The models are good enough to demonstrate the pipeline pattern — and in production, you'd invest more in training data and hyperparameter tuning.

**"Why DistilRoBERTa and not BERT, GPT, or an LLM?"**
- DistilRoBERTa is 40% smaller and 60% faster than RoBERTa, which matters for real-time inference inside Spark. A full LLM would be too slow for streaming — you'd need a dedicated GPU-backed model server. DistilRoBERTa runs on CPU in a Spark executor with acceptable latency. That said, the pipeline pattern works with any model — you could swap in a larger model if you move inference out of Spark.

**"Could you use an LLM for zero-shot classification instead of fine-tuning?"**
- Yes, and it would actually fit the dual-model pattern well. You could run a fine-tuned DistilRoBERTa alongside a zero-shot LLM and compare their predictions. The LLM wouldn't need any labeled training data. The trade-off is cost and latency — LLM inference is orders of magnitude more expensive than a fine-tuned small model.

**"How do you handle model retraining or CI/CD for models?"**
- In this demo, models are baked into the Spark Docker image. To retrain, you'd rerun the training notebook, rebuild the image, and redeploy the Spark job. In production, you'd separate model storage from the pipeline — store models in an artifact registry or object storage, and have the Spark job pull them at startup. You'd trigger retraining when the agreement rate drops below a threshold, using the disagreement data as new training examples.

### Architecture / Scaling

**"Why run inference inside Spark instead of a dedicated model server like Seldon or KServe?"**
- Simplicity. A separate model server adds another service to deploy, scale, monitor, and secure. It also adds network latency for every prediction. Running models as PySpark UDFs keeps the pipeline as a single job. The trade-off is coupling — you can't update the model without redeploying Spark. For this demo, the simplicity wins. For a team with 10 models and a platform team, a model server makes more sense.

**"How does this scale to millions of messages per second?"**
- Kafka scales by adding partitions — each partition can be consumed by a separate Spark task in parallel. Spark scales by adding executors — more executors process more micro-batches concurrently. The Quarkus consumer can be replicated behind a load balancer with each replica consuming from a different partition. The bottleneck would be inference latency — the transformer takes a few milliseconds per post, so you'd need enough executors to keep up with the ingest rate.

**"Why Kafka and not Pulsar, RabbitMQ, or a cloud-managed queue?"**
- Kafka is the de facto standard for event streaming. It has the strongest ecosystem for schema governance (Confluent serializers, Apicurio Registry compatibility), the best Kubernetes operator (Strimzi), and the widest adoption. Pulsar would also work. RabbitMQ is more of a message queue than an event stream — it doesn't have the same replay and partitioning semantics.

**"Why in-memory analytics instead of a database?"**
- For the demo, it keeps things simple — no extra infrastructure. In production, you'd persist to InfluxDB, TimescaleDB, or even PostgreSQL. The Quarkus consumer is designed so you could swap the ConcurrentHashMap-based storage for a database client without changing the REST endpoints.

### Schema Governance / Apicurio

**"Why JSON Schema and not Avro or Protobuf?"**
- Readability. JSON Schema is human-readable, the messages are already JSON, and the schemas are easy to inspect and understand. Avro would give you more compact serialization and better schema evolution support, but for a demo where I want the audience to see the schemas on a slide, JSON Schema is the better choice. Apicurio Registry supports all three — you can switch without changing the registry.

**"What happens if someone publishes a message that doesn't match the schema?"**
- The Confluent JSON Schema serializer validates at the producer side — the message is rejected before it even reaches Kafka. The producer gets a serialization exception. On the consumer side, the Quarkus app also validates incoming messages — if a malformed message somehow gets through, it goes to the Dead Letter Queue instead of crashing the consumer.

**"How do you handle schema evolution in production?"**
- Apicurio Registry supports compatibility rules per artifact. You can set backward compatibility, which means new schemas must be able to read data written by the old schema. In practice, this means you can add optional fields but not remove required ones. The registry rejects incompatible schema updates at registration time, before any data flows.

### Kubernetes / Operations

**"How long does it take to deploy the full pipeline from scratch?"**
- About 10-15 minutes on Minikube. The longest part is pulling container images (Kafka, Spark, Apicurio) and waiting for Spark to load the ML models. The Kubernetes manifests are all declarative — it's a sequence of kubectl apply commands.

**"What happens if a pod crashes mid-pipeline?"**
- Kafka retains messages, so no data is lost. If the producer crashes, it restarts and continues from where it left off in the CSV files. If Spark crashes, it restarts and replays unprocessed Kafka offsets. If the consumer crashes, it restarts and resumes from its last committed offset. The in-memory statistics are lost on consumer restart — that's the main thing you'd fix for production by persisting to a database.

**"Why Strimzi and not a managed Kafka service?"**
- For the demo, Strimzi keeps everything self-contained — no cloud accounts, no external dependencies. You can run the entire pipeline on a laptop with Minikube. In production, a managed Kafka service (Confluent Cloud, Amazon MSK, Red Hat Streams) would reduce operational burden. The pipeline code wouldn't change — only the Kafka bootstrap URL.

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
| Production Considerations | 5 min | 45 min |
| Conclusions & Wrap Up | 5 min | 50 min |
