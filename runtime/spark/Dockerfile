FROM apache/spark:3.5.5

WORKDIR /opt/spark/jobs

# Copy inference script and model artifacts
COPY reddit_flair_spark_inference.py .
COPY reddit_classifier.pkl .
COPY LSA_topics.pkl .
COPY vectorizer.pkl .
COPY reddit_flair_classifier /opt/spark/models/reddit_flairs

USER root

RUN python3 -m pip install --no-cache-dir --upgrade pip

RUN pip install --no-cache-dir --force-reinstall \
    scikit-learn==1.3.2 joblib numpy==1.24.4 pandas==2.0.3 \
    transformers==4.37.2 torch==2.4.1 accelerate==0.34.2

# Pre-validate model loading at build time
RUN python3 -c "\
import torch, joblib; \
from transformers import AutoTokenizer, AutoModelForSequenceClassification; \
model_dir = '/opt/spark/models/reddit_flairs'; \
AutoTokenizer.from_pretrained(model_dir, local_files_only=True, use_fast=False); \
AutoModelForSequenceClassification.from_pretrained(model_dir, local_files_only=True); \
joblib.load(f'{model_dir}/reddit_flair_label_encoder.joblib'); \
print('Model, tokenizer, and label encoder loaded successfully.')"

# Kafka connector JARs
RUN mkdir -p /opt/spark/jars && \
    curl -o /opt/spark/jars/spark-sql-kafka-0-10_2.12-3.5.0.jar \
        https://repo1.maven.org/maven2/org/apache/spark/spark-sql-kafka-0-10_2.12/3.5.0/spark-sql-kafka-0-10_2.12-3.5.0.jar && \
    curl -o /opt/spark/jars/kafka-clients-3.5.0.jar \
        https://repo1.maven.org/maven2/org/apache/kafka/kafka-clients/3.5.0/kafka-clients-3.5.0.jar && \
    curl -o /opt/spark/jars/spark-token-provider-kafka-0-10_2.12-3.5.0.jar \
        https://repo1.maven.org/maven2/org/apache/spark/spark-token-provider-kafka-0-10_2.12/3.5.0/spark-token-provider-kafka-0-10_2.12-3.5.0.jar && \
    curl -o /opt/spark/jars/commons-pool2-2.11.1.jar \
        https://repo1.maven.org/maven2/org/apache/commons/commons-pool2/2.11.1/commons-pool2-2.11.1.jar

USER 185

CMD ["spark-submit", "--jars", "/opt/spark/jars/spark-sql-kafka-0-10_2.12-3.5.0.jar,/opt/spark/jars/kafka-clients-3.5.0.jar,/opt/spark/jars/spark-token-provider-kafka-0-10_2.12-3.5.0.jar,/opt/spark/jars/commons-pool2-2.11.1.jar", "/opt/spark/jobs/reddit_flair_spark_inference.py"]
