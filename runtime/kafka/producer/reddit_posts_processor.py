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

# Initialize Kafka Producer
producer = KafkaProducer(
    bootstrap_servers=KAFKA_BROKER,
    value_serializer=lambda v: json.dumps(v).encode("utf-8"),
    acks="all",
    retries=5,
    request_timeout_ms=60000,
    max_block_ms=60000
)
print("Kafka producer connected successfully!")

# Function to convert timestamp to human-readable format
def get_date(created):
    return dt.datetime.fromtimestamp(created).isoformat()

def on_send_success(record_metadata):
    print(f"Sent to {record_metadata.topic} partition {record_metadata.partition} offset {record_metadata.offset}")

def on_send_error(excp):
    print(f"Send failed: {excp}")

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

                # Send to Kafka
                producer.send(KAFKA_TOPIC, value=msg) \
                    .add_callback(on_send_success) \
                    .add_errback(on_send_error)

        time.sleep(POLL_INTERVAL)

    except Exception as e:
        print(f"Error: {e}")
        time.sleep(30)
