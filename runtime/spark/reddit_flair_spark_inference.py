from pyspark.sql import SparkSession
from pyspark.sql.functions import col, from_json
from pyspark.sql.types import StructType, StringType

from pyspark.sql.functions import udf

import pickle

# Load Pre-Trained Model
with open("vectorizer.pkl", "rb") as file:
    tfidf = pickle.load(file)

with open("LSA_topics.pkl", "rb") as file:
    tsvd = pickle.load(file)

with open("reddit_classifier.pkl", "rb") as file:
    classifier = pickle.load(file)

flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel', 'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

# Initialize Spark Session
spark = SparkSession.builder \
    .appName("RedditKafkaSparkInference") \
    .config("spark.sql.shuffle.partitions", "2") \
    .config("spark.executor.instances", "2") \
    .getOrCreate()

# Define Kafka source
kafka_bootstrap_servers = "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093"

# Define Kafka topic schema
schema = StructType().add("id", StringType()).add("title", StringType()).add("content", StringType())

# Read from Kafka with checkpointing to avoid duplicate processing
df = spark.readStream .format("kafka") .option("kafka.bootstrap.servers", kafka_bootstrap_servers) .option("subscribe", "reddit-stream") .option("startingOffsets", "latest").option("failOnDataLoss", "false").load()

# Parse JSON messages
parsed_df = df.select(from_json(col("value").cast("string"), schema).alias("data")).select("data.*")

def predict_flair(content):
    X = tfidf.transform([content])
    X = tsvd.transform(X)
    return flairs[classifier.predict(X)[0]]

predict_udf = udf(predict_flair, StringType())

# Apply prediction model to data
predicted_df = parsed_df.withColumn("predicted_flair", predict_udf(col("content")))

# Write processed results to Kafka (Avoid duplicates with checkpointing)
query = predicted_df.selectExpr("to_json(struct(*)) AS value").writeStream .format("kafka") .option("kafka.bootstrap.servers", kafka_bootstrap_servers) .option("topic", "kafka-predictions").option("checkpointLocation", "/tmp/spark-checkpoints").trigger(processingTime="1 minute") .start()

query.awaitTermination()
