import os
import praw
import json
import time
import datetime as dt
import tldextract
import pandas as pd

from kafka import KafkaProducer

# Import the cleaning functions from cleaning.py
import cleaning

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

# OpenTelemetry setup
resource = Resource.create({"service.name": "reddit-producer"})
provider = TracerProvider(resource=resource)
otlp_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "http://jaeger.reddit-realtime.svc:4317")
exporter = OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True)
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)
tracer = trace.get_tracer("reddit-producer")
propagator = TraceContextTextMapPropagator()

# Reddit API Credentials
reddit = praw.Reddit(
    client_id=os.environ["REDDIT_CLIENT_ID"],
    client_secret=os.environ["REDDIT_CLIENT_SECRET"],
    user_agent=os.environ.get("REDDIT_USER_AGENT", "predictions")
)

# Kafka Configuration
KAFKA_BROKER = os.environ.get("KAFKA_BROKER", "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093")
KAFKA_TOPIC = "reddit-stream"

# Initialize Kafka Producer
try:
    producer = KafkaProducer(
        bootstrap_servers=KAFKA_BROKER,
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        retries=5,  # Retry on failure
        acks="all",  # Ensure message is fully committed
        request_timeout_ms=60000,  # Increase timeout
        linger_ms=10,  # Reduce batch wait time
        max_block_ms=60000  # Ensure producer does not hang indefinitely
    )
    print(" Kafka producer connected successfully!")
except Exception as e:
    print(f" Kafka connection failed: {e}")

# Function to convert timestamp to human-readable format
def get_date(created):
    return dt.datetime.fromtimestamp(created).isoformat()

DLQ_FILE = os.environ.get("DLQ_FILE", "/tmp/producer-dlq.jsonl")

def on_send_success(record_metadata):
    print(f" Message sent successfully to {record_metadata.topic} partition {record_metadata.partition} at offset {record_metadata.offset}")

def on_send_error(excp, post_data=None):
    print(f" Message send failed: {excp}")
    if post_data:
        try:
            with open(DLQ_FILE, "a") as f:
                dlq_record = {"error": str(excp), "data": post_data, "timestamp": dt.datetime.now().isoformat()}
                f.write(json.dumps(dlq_record) + "\n")
            print(f" Failed message written to DLQ file: {DLQ_FILE}")
        except Exception as dlq_err:
            print(f" Failed to write to DLQ file: {dlq_err}")

# List of categories to track
flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel', 'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

# Set of processed post IDs to avoid duplicates
processed_ids = set()

print(" Streaming and cleaning new Reddit posts to Kafka...")

while True:
    try:
        subreddit = reddit.subreddit("AskEurope")

        for flair in flairs:
            with tracer.start_as_current_span("reddit-api-search", attributes={"reddit.flair": flair}):
                new_posts = subreddit.search(query=f"flair:{flair}", time_filter="week", limit=200)

            for submission in new_posts:
                if submission.id not in processed_ids:
                    # Mark as processed
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

                    # Handle special cases
                    if submission.is_self:
                        domain = "self-post"
                    elif domain == "youtu.be":
                        domain = "youtube.com"
                    elif domain == "redd.it":
                        domain = "reddit.com"

                    post_data["domain"] = domain

                    # Extract top-level comments and clean them
                    submission.comments.replace_more(limit=10)
                    comment = ' '
                    for top_level_comment in submission.comments:
                        comment += ' ' + top_level_comment.body
                    post_data["comments"].append(comment)

                    data = pd.DataFrame(post_data)

                    cleaning.clean_text(data, 'title')
                    cleaning.clean_text(data, 'body')
                    cleaning.clean_text(data, 'comments')

                    data['content'] = data.title + ' ' + data.body + ' ' + data.comments + ' ' + data.domain

                    post_data = {
                        "id": data['id'].iloc[0],
                        "content": data['content'].iloc[0]
                    }

                    # Send data to Kafka with trace context
                    with tracer.start_as_current_span("produce-reddit-post", attributes={
                        "reddit.post.id": post_data["id"],
                        "kafka.topic": KAFKA_TOPIC,
                    }) as span:
                        headers = []
                        carrier = {}
                        propagator.inject(carrier)
                        for k, v in carrier.items():
                            headers.append((k, v.encode("utf-8")))
                        msg = post_data  # capture for errback closure
                        producer.send(KAFKA_TOPIC, value=post_data, headers=headers).add_callback(on_send_success).add_errback(lambda excp, d=msg: on_send_error(excp, d))

        # Wait before checking for new posts
        time.sleep(300)  # Sleep for 5 minutes

    except Exception as e:
        print(f" Error: {e}")
        time.sleep(60)  # Retry after 1 minute if an error occurs
