FROM registry.access.redhat.com/ubi8/python-39

WORKDIR /app

COPY reddit_posts_processor.py .
COPY cleaning.py .
COPY stop.txt .

COPY nltk_data/ /usr/local/share/nltk_data/
ENV NLTK_DATA="/usr/local/share/nltk_data"

RUN pip install --no-cache-dir praw confluent-kafka[json] tldextract nltk pandas requests jsonschema

CMD ["python", "-u", "reddit_posts_processor.py"]
