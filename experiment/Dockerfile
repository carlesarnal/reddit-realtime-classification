FROM apache/spark:3.5.3-python3
USER root
RUN pip install --no-cache-dir scikit-learn==1.3.2
WORKDIR /lab
ENTRYPOINT ["/opt/spark/bin/spark-submit", "--master", "local[2]", "/lab/load_experiment.py"]
