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
    client_id="zxFFHFUXZ3xkhPrkTrDaFg",
    client_secret="jFowmw-Pda2y5EaI7E0X7VMxjDbPYQ",
    user_agent="predictions"
)

# Kafka Configuration
KAFKA_BROKER = "reddit-posts-kafka-bootstrap.reddit-realtime.svc:9093"
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
    print("✅ Kafka producer connected successfully!")
except Exception as e:
    print(f"❌ Kafka connection failed: {e}")

# Function to convert timestamp to human-readable format
def get_date(created):
    return dt.datetime.fromtimestamp(created).isoformat()

def on_send_success(record_metadata):
    print(f"✔ Message sent successfully to {record_metadata.topic} partition {record_metadata.partition} at offset {record_metadata.offset}")

def on_send_error(excp):
    print(f"❌ Message send failed: {excp}")

# List of categories to track
flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel', 'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

# Set of processed post IDs to avoid duplicates
processed_ids = set()

print(" Streaming and cleaning new Reddit posts to Kafka...")

while True:
    try:
        subreddit = reddit.subreddit("AskEurope")

        for flair in flairs:
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

                    # Send data to Kafka
                    producer.send(KAFKA_TOPIC, value=post_data).add_callback(on_send_success).add_errback(on_send_error)

        # Wait before checking for new posts
        time.sleep(300)  # Sleep for 5 minutes

    except Exception as e:
        print(f" Error: {e}")
        time.sleep(60)  # Retry after 1 minute if an error occurs
