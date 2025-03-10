# Use an official Spark base image
FROM apache/spark:3.5.0

# Set working directory inside the container
WORKDIR /opt/spark/jobs

# Copy the Spark Streaming script
COPY reddit_flair_spark_inference.py .

# Copy the pre-trained model
COPY reddit_classifier.pkl .

# Switch to root user temporarily to install packages
USER root

# Install required Python libraries in user-accessible location
RUN pip install --no-cache-dir --user scikit-learn joblib numpy pandas

# Switch back to Spark user
USER 185

# Command to run the Spark script
CMD ["spark-submit", "/opt/spark/jobs/reddit_flair_spark_inference.py"]
