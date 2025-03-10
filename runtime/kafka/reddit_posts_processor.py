import praw
import json
import time
import datetime as dt
import tldextract
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
KAFKA_BROKER = "reddit-posts-kafka-bootstrap.kafka:9093"
KAFKA_TOPIC = "reddit-stream"

# Initialize Kafka Producer
producer = KafkaProducer(
    bootstrap_servers=KAFKA_BROKER,
    value_serializer=lambda v: json.dumps(v).encode("utf-8")
)

# Function to convert timestamp to human-readable format
def get_date(created):
    return dt.datetime.fromtimestamp(created).isoformat()

# List of categories to track
flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel', 'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

# Set of processed post IDs to avoid duplicates
processed_ids = set()

print(" Streaming and cleaning new Reddit posts to Kafka...")

while True:
    try:
        subreddit = reddit.subreddit("AskEurope")

        for flair in flairs:
            new_posts = subreddit.search(query=f"flair:{flair}", time_filter="hour", limit=50)

            for submission in new_posts:
                if submission.id not in processed_ids:
                    # Mark as processed
                    processed_ids.add(submission.id)

                    # Extract post data
                    post_data = {
                        "id": submission.id,
                        "title": cleaning.clean_text(submission.title),
                        "body": cleaning.clean_text(submission.selftext),
                        "flair": submission.link_flair_text,
                        "score": submission.score,
                        "url": submission.url,
                        "comments": [],
                        "timestamp": get_date(submission.created)
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
                    submission.comments.replace_more(limit=0)
                    post_data["comments"] = cleaning.clean_text(" ".join([comment.body for comment in submission.comments[:5]]))

                    post_data = {
                        "id": post_data['id'],
                        "content": post_data['title'] +' '+ post_data['body'] +' '+ post_data['comments'] +' '+ post_data['domain']
                    }

                    # Send data to Kafka
                    producer.send(KAFKA_TOPIC, value=post_data)
                    print(f" Sent post {submission.id} to Kafka.")

        # Wait before checking for new posts
        time.sleep(300)  # Sleep for 5 minutes

    except Exception as e:
        print(f" Error: {e}")
        time.sleep(60)  # Retry after 1 minute if an error occurs
