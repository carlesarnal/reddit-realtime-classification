import praw
import pandas as pd
import datetime as dt
from tqdm import tqdm
import tldextract
import time
import os

# --- Config ---
SUBREDDIT_NAME = "AskEurope"
FLAIRS = {
    'Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel',
    'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign'
}
COMMENTS_LIMIT = 30
OUTPUT_DIR = "reddit_daily_data"
MASTER_CSV = os.path.join(OUTPUT_DIR, "reddit_eur_data.csv")
DAILY_CSV = os.path.join(OUTPUT_DIR, f"reddit_{dt.date.today()}.csv")

# --- Reddit API ---
reddit = praw.Reddit(
    client_id="zxFFHFUXZ3xkhPrkTrDaFg",
    client_secret="jFowmw-Pda2y5EaI7E0X7VMxjDbPYQ",
    user_agent="predictions"
)

def get_date(created):
    return dt.datetime.fromtimestamp(created)

# --- Load existing data to avoid duplicates ---
if not os.path.exists(OUTPUT_DIR):
    os.makedirs(OUTPUT_DIR)

if os.path.exists(MASTER_CSV):
    existing = pd.read_csv(MASTER_CSV)
    existing_ids = set(existing["id"])
else:
    existing = pd.DataFrame()
    existing_ids = set()

# --- Data container ---
topics_dict = {
    "id": [], "flair": [], "title": [], "body": [], "comments": [], "score": [],
    "author": [], "url": [], "domain": [], "comms_num": [], "created": []
}

print("Collecting new posts...")
for submission in tqdm(reddit.subreddit(SUBREDDIT_NAME).new(limit=None), desc="Fetching posts"):
    if submission.link_flair_text not in FLAIRS:
        continue
    if submission.id in existing_ids:
        continue

    try:
        submission.comments.replace_more(limit=COMMENTS_LIMIT)
        comments = ' '.join([c.body for c in submission.comments])
    except:
        comments = ''

    tld = tldextract.extract(submission.url)
    domain = f"{tld.domain}.{tld.suffix}" if not submission.is_self else "self-post"
    domain = {
        "redd.it": "reddit.com",
        "youtu.be": "youtube.com"
    }.get(domain, domain)

    topics_dict["flair"].append(submission.link_flair_text)
    topics_dict["title"].append(submission.title)
    topics_dict["score"].append(submission.score)
    topics_dict["id"].append(submission.id)
    topics_dict["url"].append(submission.url)
    topics_dict["comms_num"].append(submission.num_comments)
    topics_dict["created"].append(submission.created_utc)
    topics_dict["body"].append(submission.selftext)
    topics_dict["author"].append(str(submission.author))
    topics_dict["domain"].append(domain)
    topics_dict["comments"].append(comments)

    time.sleep(0.1)  # respect rate limit

# --- Convert to DataFrame ---
new_data = pd.DataFrame(topics_dict)
if new_data.empty:
    print("🟡 No new posts found today.")
    exit(0)

new_data["timestamp"] = new_data["created"].apply(get_date)
del new_data["created"]

# --- Save daily and append to master ---
new_data.to_csv(DAILY_CSV, index=False)
print(f"📁 Saved daily CSV: {DAILY_CSV}")

if not existing.empty:
    combined = pd.concat([existing, new_data], ignore_index=True)
    combined.drop_duplicates(subset="id", inplace=True)
else:
    combined = new_data

combined.to_csv(MASTER_CSV, index=False)
print(f"📦 Appended {len(new_data)} new posts to {MASTER_CSV}")