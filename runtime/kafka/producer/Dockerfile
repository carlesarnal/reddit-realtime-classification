# Use an official lightweight Python image
FROM python:3.13

# Set working directory inside the container
WORKDIR /app

# Copy application files into the container
COPY reddit_posts_processor.py .
COPY cleaning.py .
COPY stop.txt .

# Copy NLTK pre-downloaded data into a location that Python can access
COPY nltk_data/ /usr/local/share/nltk_data/

# Set environment variable so NLTK knows where to look
ENV NLTK_DATA="/usr/local/share/nltk_data"

# Install required dependencies
RUN pip install praw kafka-python tldextract nltk pandas

# Verify that NLTK files exist
RUN ls -l /usr/local/share/nltk_data

# Command to run the Kafka producer script
CMD ["python", "-u", "reddit_posts_processor.py"]
