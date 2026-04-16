import os
import glob
import json
import time
import pandas as pd

from confluent_kafka import Producer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.json_schema import JSONSerializer

# Import the cleaning functions from cleaning.py
import cleaning

# Kafka Configuration
KAFKA_BROKER = os.environ.get("KAFKA_BROKER", "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093")
KAFKA_TOPIC = "reddit-stream"
SEND_INTERVAL = 2  # seconds between messages (simulates real-time stream)

# Apicurio Registry — Confluent-compatible API endpoint
REGISTRY_URL = os.environ.get("REGISTRY_URL", "http://apicurio-registry.reddit-realtime.svc:8080")
REGISTRY_CCOMPAT_URL = REGISTRY_URL + "/apis/ccompat/v7"

# JSON Schema for the reddit-stream topic value
SCHEMA_STR = json.dumps({
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "urn:reddit:stream:value",
    "title": "RedditStreamValue",
    "type": "object",
    "properties": {
        "id": {"type": "string", "description": "Reddit post ID"},
        "content": {"type": "string", "description": "Cleaned and concatenated post content"}
    },
    "required": ["id", "content"]
})

# Initialize Schema Registry client (using Apicurio's Confluent-compatible API)
schema_registry_client = SchemaRegistryClient({"url": REGISTRY_CCOMPAT_URL})

# Create JSON serializer — validates and encodes with schema ID (Confluent wire format)
json_serializer = JSONSerializer(SCHEMA_STR, schema_registry_client)
print(f"JSON Schema serializer connected to Apicurio Registry at {REGISTRY_CCOMPAT_URL}")

# Initialize Confluent Kafka Producer
producer = Producer({
    "bootstrap.servers": KAFKA_BROKER,
    "acks": "all",
    "retries": 5,
    "request.timeout.ms": 60000,
})
print("Kafka producer connected successfully!")

def delivery_callback(err, msg):
    if err:
        print(f"Send failed: {err}")
    else:
        print(f"Sent to {msg.topic()} partition {msg.partition()} offset {msg.offset()}")

# Load all CSV files from the data directory
DATA_DIR = "/app/data"
csv_files = sorted(glob.glob(os.path.join(DATA_DIR, "*.csv")))
print(f"Found {len(csv_files)} CSV files in {DATA_DIR}")

print("Streaming cleaned Reddit posts from CSV files to Kafka...")

for csv_file in csv_files:
    print(f"Processing {os.path.basename(csv_file)}...")
    try:
        df = pd.read_csv(csv_file, dtype=str).fillna("")
    except Exception as e:
        print(f"Error reading {csv_file}: {e}")
        continue

    for _, row in df.iterrows():
        try:
            # Build a single-row DataFrame for the cleaning pipeline
            post = pd.DataFrame([{
                "id": row.get("id", "unknown"),
                "title": row.get("title", ""),
                "body": row.get("body", ""),
                "comments": row.get("comments", ""),
                "domain": row.get("domain", "self-post"),
            }])

            cleaning.clean_text(post, "title")
            cleaning.clean_text(post, "body")
            cleaning.clean_text(post, "comments")
            post["content"] = post.title + " " + post.body + " " + post.comments + " " + post.domain

            msg = {
                "id": post["id"].iloc[0],
                "content": post["content"].iloc[0],
            }

            producer.produce(
                topic=KAFKA_TOPIC,
                value=json_serializer(msg, SerializationContext(KAFKA_TOPIC, MessageField.VALUE)),
                on_delivery=delivery_callback,
            )
            producer.poll(0)
            time.sleep(SEND_INTERVAL)

        except Exception as e:
            print(f"Error processing post {row.get('id', '?')}: {e}")

    producer.flush()
    print(f"Finished {os.path.basename(csv_file)}")

print("All CSV files processed. Sleeping indefinitely to keep pod alive...")
while True:
    time.sleep(3600)
