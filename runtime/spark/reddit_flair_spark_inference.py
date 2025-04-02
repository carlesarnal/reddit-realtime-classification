# reddit_transformer_spark.py

from pyspark.sql import SparkSession
from pyspark.sql.functions import col, from_json, udf
from pyspark.sql.types import StructType, StringType

from transformers import AutoTokenizer, AutoModelForSequenceClassification
import torch
import joblib

# Load Hugging Face model, tokenizer and label encoder
model_dir = "./reddit_flair_classifier"
model = AutoModelForSequenceClassification.from_pretrained(model_dir)
tokenizer = AutoTokenizer.from_pretrained(model_dir)
label_encoder = joblib.load("./reddit_flair_classifier/reddit_flair_label_encoder.joblib")

# Ensure model runs on CPU (Spark workers will default to this)
device = torch.device("cpu")
model.to(device)
model.eval()

# Define UDF for flair prediction
def predict_flair_transformer(text):
    if not text or len(text.strip()) == 0:
        return "Unknown"
    inputs = tokenizer(text, return_tensors="pt", truncation=True, padding=True, max_length=512)
    inputs = {k: v.to(device) for k, v in inputs.items()}
    with torch.no_grad():
        outputs = model(**inputs)
    probs = torch.nn.functional.softmax(outputs.logits, dim=-1)
    pred_class = torch.argmax(probs, dim=1).item()
    return label_encoder.classes_[pred_class]

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
    .option("startingOffsets", "latest") \
    .option("failOnDataLoss", "false") \
    .load()

# Parse JSON messages
df_parsed = df.select(from_json(col("value").cast("string"), schema).alias("data")).select("data.*")

# Apply transformer model UDF
df_with_prediction = df_parsed.withColumn("predicted_flair", predict_udf(col("content")))

# Write predictions to Kafka
query = df_with_prediction.selectExpr("to_json(struct(*)) AS value") \
    .writeStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", kafka_bootstrap_servers) \
    .option("topic", "kafka-predictions") \
    .option("checkpointLocation", "/tmp/spark-checkpoints") \
    .trigger(processingTime="1 minute") \
    .start()

query.awaitTermination()