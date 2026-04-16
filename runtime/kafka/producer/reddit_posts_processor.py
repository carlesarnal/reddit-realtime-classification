import os
import praw
import json
import time
import datetime as dt
import tldextract
import pandas as pd

from confluent_kafka import Producer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.json_schema import JSONSerializer

# Import the cleaning functions from cleaning.py
import cleaning

# Reddit API Credentials
reddit = praw.Reddit(
    client_id=os.environ["REDDIT_CLIENT_ID"],
    client_secret=os.environ["REDDIT_CLIENT_SECRET"],
    user_agent=os.environ.get("REDDIT_USER_AGENT", "predictions")
)

# Kafka Configuration
KAFKA_BROKER = os.environ.get("KAFKA_BROKER", "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093")
KAFKA_TOPIC = "reddit-stream"
POLL_INTERVAL = 300  # 5 minutes between cycles

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

# Function to convert timestamp to human-readable format
def get_date(created):
    return dt.datetime.fromtimestamp(created).isoformat()

def delivery_callback(err, msg):
    if err:
        print(f"Send failed: {err}")
    else:
        print(f"Sent to {msg.topic()} partition {msg.partition()} offset {msg.offset()}")

# Flair categories to track
flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel',
          'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

# Track processed post IDs to avoid duplicates
processed_ids = set()

print("Streaming and cleaning Reddit posts to Kafka...")

while True:
    try:
        subreddit = reddit.subreddit("AskEurope")

        for flair in flairs:
            new_posts = subreddit.search(query=f"flair:{flair}", time_filter="week", limit=200)

            for submission in new_posts:
                if submission.id in processed_ids:
                    continue

                processed_ids.add(submission.id)

                # Extract post data
                post_data = {
                    "id": submission.id,
                    "title": submission.title,
                    "body": submission.selftext,
                    "flair": submission.link_flair_text,
                    "score": submission.score,
                    "url": submission.url,
                    "comments": [],
                    "timestamp": submission.created,
                    "comms_num": submission.num_comments,
                }

                # Extract domain
                tld = tldextract.extract(submission.url)
                domain = f"{tld.domain}.{tld.suffix}"
                if submission.is_self:
                    domain = "self-post"
                elif domain == "youtu.be":
                    domain = "youtube.com"
                elif domain == "redd.it":
                    domain = "reddit.com"
                post_data["domain"] = domain

                # Extract and concatenate top-level comments
                submission.comments.replace_more(limit=10)
                comment = ' '
                for top_level_comment in submission.comments:
                    comment += ' ' + top_level_comment.body
                post_data["comments"].append(comment)

                # Clean text using NLP pipeline
                data = pd.DataFrame(post_data)
                cleaning.clean_text(data, 'title')
                cleaning.clean_text(data, 'body')
                cleaning.clean_text(data, 'comments')
                data['content'] = data.title + ' ' + data.body + ' ' + data.comments + ' ' + data.domain

                msg = {
                    "id": data['id'].iloc[0],
                    "content": data['content'].iloc[0]
                }

                # Serialize with JSON Schema (validates + adds Confluent wire format header)
                # and send to Kafka
                producer.produce(
                    topic=KAFKA_TOPIC,
                    value=json_serializer(msg, SerializationContext(KAFKA_TOPIC, MessageField.VALUE)),
                    on_delivery=delivery_callback
                )
                producer.poll(0)

        producer.flush()
        time.sleep(POLL_INTERVAL)

    except Exception as e:
        print(f"Error: {e}")
        time.sleep(30)
