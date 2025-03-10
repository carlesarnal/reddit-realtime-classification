import praw
import pandas as pd
import datetime as dt
from tqdm import tqdm
import tldextract


# Reddit API Credentials
reddit = praw.Reddit(
    client_id="zxFFHFUXZ3xkhPrkTrDaFg",
    client_secret="jFowmw-Pda2y5EaI7E0X7VMxjDbPYQ",
    user_agent="predictions"
)

def get_date(created):
    return dt.datetime.fromtimestamp(created)

subreddit = reddit.subreddit('AskEurope')

flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel', 'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

topics_dict = {"id": [], "flair": [], "title": [], "body": [], "comments": [], "score": [], "author": [], "url": [],
               "domain": [], "comms_num": [], "created": []}

# Using tqdm progress bar to track iterations
with tqdm(total=len(flairs) * 125) as pbar:
    for flair in flairs:
        get_subreddits = subreddit.search(query=f"flair:{flair}", time_filter='year', limit=150)
        for submission in get_subreddits:
            topics_dict["flair"].append(submission.link_flair_text)
            topics_dict["title"].append(submission.title)
            topics_dict["score"].append(submission.score)
            topics_dict["id"].append(submission.id)
            topics_dict["url"].append(submission.url)
            topics_dict["comms_num"].append(submission.num_comments)
            topics_dict["created"].append(submission.created)
            topics_dict["body"].append(submission.selftext)
            topics_dict["author"].append(submission.author)

            # Using top-level-domain extraction methods to find domain of URLs
            tld = tldextract.extract(submission.url)
            d = tld.domain + "." + tld.suffix

            # Conditions for some exceptions
            if submission.is_self == True:
                d = "self-post"
            if d == "youtu.be":
                d = "youtube.com"
            if d == "redd.it":
                d = "reddit.com"

            topics_dict["domain"].append(d)

            submission.comments.replace_more(limit=10)
            comment = ' '
            for top_level_comment in submission.comments:
                comment += ' ' + top_level_comment.body
            topics_dict["comments"].append(comment)
            pbar.update(1)

data = pd.DataFrame(topics_dict)

# Converting timestamp to datetime format
_timestamp = data["created"].apply(get_date)
data = data.assign(timestamp = _timestamp)
del data['created']

# Shuffling the rows
data = data.sample(frac=1).reset_index(drop=True)

# Saving the data in csv file
data.to_csv('reddit_eur_data.csv', index=False)