from kafka import KafkaConsumer
import json

# Kafka configuration
KAFKA_BROKER = "localhost:9093"  # Change if running inside Kubernetes
KAFKA_TOPIC = "reddit-stream"

# Initialize Kafka Consumer
consumer = KafkaConsumer(
    KAFKA_TOPIC,
    bootstrap_servers=KAFKA_BROKER,
    value_deserializer=lambda v: json.loads(v.decode("utf-8")),
    auto_offset_reset="earliest",  # Ensure we consume messages from the start
    enable_auto_commit=True,
    group_id="test-group"
)

print(f"🟢 Listening for messages on topic '{KAFKA_TOPIC}'...\n")

# Consume messages from Kafka
for message in consumer:
    print(f"📩 Received message: {message.value}")
