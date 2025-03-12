# Use an official Spark base image
FROM apache/spark:3.5.5

# Set working directory inside the container
WORKDIR /opt/spark/jobs

# Copy the Spark Streaming script and model
COPY reddit_flair_spark_inference.py .

# Copy pre-trained model
COPY reddit_classifier.pkl .
COPY LSA_topics.pkl .
COPY vectorizer.pkl .

# Switch to root user temporarily to install packages
USER root

# Ensure pip is updated to prevent issues
RUN python3 -m pip install --no-cache-dir --upgrade pip

# Install required Python libraries globally (force-reinstall to avoid corruption)
RUN pip install --no-cache-dir --force-reinstall scikit-learn==1.3.2 joblib numpy==1.24.4 pandas==2.0.3

# Create JARs directory for Spark dependencies
RUN mkdir -p /opt/spark/jars

RUN curl -o /opt/spark/jars/spark-sql-kafka-0-10_2.12-3.5.0.jar \
    https://repo1.maven.org/maven2/org/apache/spark/spark-sql-kafka-0-10_2.12/3.5.0/spark-sql-kafka-0-10_2.12-3.5.0.jar && \
    curl -o /opt/spark/jars/kafka-clients-3.5.0.jar \
    https://repo1.maven.org/maven2/org/apache/kafka/kafka-clients/3.5.0/kafka-clients-3.5.0.jar && \
    curl -o /opt/spark/jars/spark-token-provider-kafka-0-10_2.12-3.5.0.jar \
    https://repo1.maven.org/maven2/org/apache/spark/spark-token-provider-kafka-0-10_2.12/3.5.0/spark-token-provider-kafka-0-10_2.12-3.5.0.jar && \
    curl -o /opt/spark/jars/commons-pool2-2.11.1.jar \
    https://repo1.maven.org/maven2/org/apache/commons/commons-pool2/2.11.1/commons-pool2-2.11.1.jar

# Switch back to Spark user
USER 185

# Command to run the Spark script with all required dependencies
CMD ["spark-submit", "--jars", "/opt/spark/jars/spark-sql-kafka-0-10_2.12-3.5.0.jar,/opt/spark/jars/kafka-clients-3.5.0.jar,/opt/spark/jars/spark-token-provider-kafka-0-10_2.12-3.5.0.jar,/opt/spark/jars/commons-pool2-2.11.1.jar", "/opt/spark/jobs/reddit_flair_spark_inference.py"]