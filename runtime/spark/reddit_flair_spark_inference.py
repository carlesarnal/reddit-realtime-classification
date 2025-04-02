# reddit_transformer_spark.py

import os
from pyspark.sql import SparkSession
from pyspark.sql.functions import col, from_json, udf
from pyspark.sql.types import StructType, StringType

from transformers import AutoTokenizer, AutoModelForSequenceClassification
import torch
import joblib
import json

# Load model and tokenizer from local path (pre-downloaded in Docker)
model_dir = "/opt/spark/models/reddit_flairs"
model = AutoModelForSequenceClassification.from_pretrained(model_dir)
tokenizer = AutoTokenizer.from_pretrained(model_dir)
label_encoder = joblib.load(os.path.join(model_dir, "reddit_flair_label_encoder.joblib"))

# Ensure model runs on CPU (Spark workers will default to this)
device = torch.device("cpu")
model.to(device)
model.eval()

# Define UDF for flair prediction with confidence
def predict_flair_transformer(row):
    content = row["content"]
    if not content or len(content.strip()) == 0:
        flair = "Unknown"
        confidence = 0.0
    else:
        inputs = tokenizer(content, return_tensors="pt", truncation=True, padding=True, max_length=512)
        inputs = {k: v.to(device) for k, v in inputs.items()}
        with torch.no_grad():
            outputs = model(**inputs)
        probs = torch.nn.functional.softmax(outputs.logits, dim=-1)
        pred_class = torch.argmax(probs, dim=1).item()
        flair = label_encoder.classes_[pred_class]
        confidence = float(probs[0][pred_class])

    return json.dumps({
        "id": row["id"],
        "title": row["title"],
        "content": content,
        "predicted_flair": flair,
        "confidence": confidence
    })

predict_udf = udf(predict_flair_transformer, StringType())

# Initialize Spark Session
spark = SparkSession.builder \
    .appName("RedditKafkaTransformerInference") \
    .config("spark.sql.shuffle.partitions", "2") \
    .config("spark.executor.instances", "2") \
    .getOrCreate()

# Define Kafka source
kafka_bootstrap_servers = "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093"

# Define Kafka topic schema
schema = StructType() \
    .add("id", StringType()) \
    .add("title", StringType()) \
    .add("content", StringType())

# Read from Kafka
df = spark.readStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", kafka_bootstrap_servers) \
    .option("subscribe", "reddit-stream") \
    .option("startingOffsets", "earliest") \
    .option("failOnDataLoss", "false") \
    .load()

# Parse JSON messages
df_parsed = df.select(from_json(col("value").cast("string"), schema).alias("data")).select("data.*")

# Apply transformer model UDF with confidence
predicted_df = df_parsed.withColumn("value", predict_udf(col("data")))

# Write predictions to Kafka
query = predicted_df.selectExpr("value") \
    .writeStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", kafka_bootstrap_servers) \
    .option("topic", "kafka-predictions") \
    .option("checkpointLocation", "/opt/spark/checkpoints/reddit-inference") \
    .trigger(processingTime="1 minute") \
    .start()

query.awaitTermination()
