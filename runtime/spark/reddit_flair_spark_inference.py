import os
import json
import joblib
import pickle
import torch

from pyspark.sql import SparkSession
from pyspark.sql.functions import col, from_json, udf, struct
from pyspark.sql.types import StructType, StringType

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource

# OpenTelemetry setup
resource = Resource.create({"service.name": "spark-inference"})
provider = TracerProvider(resource=resource)
otlp_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "http://jaeger.reddit-realtime.svc:4317")
exporter = OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True)
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)
tracer = trace.get_tracer("spark-inference")

# Hugging Face environment for offline mode
os.environ["TRANSFORMERS_CACHE"] = "/tmp/hf-cache"
os.environ["HF_DATASETS_CACHE"] = "/tmp/hf-cache"
os.environ["HF_HOME"] = "/tmp/hf-home"
os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
os.environ["HF_HUB_OFFLINE"] = "1"
os.makedirs("/tmp/hf-cache", exist_ok=True)
os.makedirs("/tmp/hf-home", exist_ok=True)

# Load Transformer model
from transformers import AutoTokenizer, AutoModelForSequenceClassification

model_dir = "/opt/spark/models/reddit_flairs"
transformer_model = AutoModelForSequenceClassification.from_pretrained(model_dir)
tokenizer = AutoTokenizer.from_pretrained(model_dir, local_files_only=True, use_fast=False)
label_encoder = joblib.load(os.path.join(model_dir, "reddit_flair_label_encoder.joblib"))

transformer_model.to("cpu")
transformer_model.eval()

# Load scikit-learn pipeline
with open("vectorizer.pkl", "rb") as f:
    tfidf = pickle.load(f)
with open("LSA_topics.pkl", "rb") as f:
    tsvd = pickle.load(f)
with open("reddit_classifier.pkl", "rb") as f:
    classifier = pickle.load(f)

flairs = label_encoder.classes_.tolist()

# Define schema
schema = StructType().add("id", StringType()).add("title", StringType()).add("content", StringType())

# DLQ topic for failed inference
DLQ_TOPIC = "reddit-inference-dlq"

# Define UDF — returns JSON with a "__dlq" flag if inference fails
def dual_model_prediction(row):
    content = row["content"]
    post_id = row["id"] or "unknown"

    try:
        transformer_flair = "Unknown"
        transformer_conf = 0.0
        sklearn_flair = "Unknown"
        sklearn_conf = 0.0

        if content and content.strip():
            with tracer.start_as_current_span("dual-model-inference", attributes={
                "reddit.post.id": post_id,
            }) as span:
                # Transformer inference
                with tracer.start_as_current_span("transformer-inference"):
                    inputs = tokenizer(content, return_tensors="pt", truncation=True, padding=True, max_length=512)
                    inputs = {k: v.to("cpu") for k, v in inputs.items()}
                    with torch.no_grad():
                        outputs = transformer_model(**inputs)
                    probs = torch.nn.functional.softmax(outputs.logits, dim=-1)
                    pred_class = torch.argmax(probs, dim=1).item()
                    transformer_flair = flairs[pred_class]
                    transformer_conf = float(probs[0][pred_class])

                # Sklearn inference
                with tracer.start_as_current_span("sklearn-inference"):
                    X = tfidf.transform([content])
                    X_reduced = tsvd.transform(X)
                    sk_probs = classifier.predict_proba(X_reduced)[0]
                    sk_class = sk_probs.argmax()
                    sklearn_flair = flairs[sk_class]
                    sklearn_conf = float(sk_probs[sk_class])

                span.set_attribute("prediction.transformer_flair", transformer_flair)
                span.set_attribute("prediction.transformer_confidence", transformer_conf)
                span.set_attribute("prediction.sklearn_flair", sklearn_flair)
                span.set_attribute("prediction.sklearn_confidence", sklearn_conf)
                span.set_attribute("prediction.models_agree", transformer_flair == sklearn_flair)

        return json.dumps({
            "id": post_id,
            "title": row["title"],
            "content": content,
            "transformer_flair": transformer_flair,
            "transformer_confidence": transformer_conf,
            "sklearn_flair": sklearn_flair,
            "sklearn_confidence": sklearn_conf
        })
    except Exception as e:
        # Return DLQ-flagged record so it can be routed to the DLQ topic
        return json.dumps({
            "__dlq": True,
            "id": post_id,
            "title": row["title"],
            "content": content,
            "error": str(e)
        })

predict_udf = udf(dual_model_prediction, StringType())

# Init Spark
spark = SparkSession.builder \
    .appName("RedditDualModelInference") \
    .config("spark.sql.shuffle.partitions", "2") \
    .config("spark.executor.instances", "2") \
    .getOrCreate()

# Kafka source
kafka_bootstrap_servers = "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093"
df = spark.readStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", kafka_bootstrap_servers) \
    .option("subscribe", "reddit-stream") \
    .option("startingOffsets", "earliest") \
    .option("failOnDataLoss", "false") \
    .option("maxOffsetsPerTrigger", "100") \
    .load()

parsed_df = df.select(from_json(col("value").cast("string"), schema).alias("data")).select("data.*")

from pyspark.sql.functions import get_json_object

# Apply dual inference
predicted_df = parsed_df.withColumn("value", predict_udf(struct("id", "title", "content")))

# Split successful predictions from DLQ records
is_dlq = get_json_object(col("value"), "$.__dlq") == "true"
success_df = predicted_df.filter(~is_dlq)
dlq_df = predicted_df.filter(is_dlq)

# Kafka sink — successful predictions
query_success = success_df.select("value") \
    .writeStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", kafka_bootstrap_servers) \
    .option("topic", "kafka-predictions") \
    .option("checkpointLocation", "/tmp/spark-checkpoints/predictions") \
    .trigger(processingTime="1 minute") \
    .start()

# Kafka sink — DLQ for failed inference
query_dlq = dlq_df.select("value") \
    .writeStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", kafka_bootstrap_servers) \
    .option("topic", DLQ_TOPIC) \
    .option("checkpointLocation", "/tmp/spark-checkpoints/dlq") \
    .trigger(processingTime="1 minute") \
    .start()

spark.streams.awaitAnyTermination()
