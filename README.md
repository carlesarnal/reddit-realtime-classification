# Reddit Realtime Flair Classification
This project is a real-time classification of Reddit posts using a pre-trained model. The model is trained on a dataset of Reddit posts from the r/AskEurope subreddit. The model is then used to classify new Reddit posts in real-time.

## Architecture

The architecture of the project is as follows:

1. **Data Collection**: Reddit posts are collected using the Reddit API. The posts are then sent to a Kafka topic.
2. **Data Processing**: The posts are read from the Kafka topic and processed using Spark Structured Streaming. The processed data is then sent to another Kafka topic.
3. **Model Inference**: The processed data is read from the Kafka topic and the model is used to classify the posts. The classified posts are then sent to another Kafka topic.
4. **Dashboard**: The classified posts are consumed by a Quarkus application and displayed in a dashboard.
5. **Monitoring**: The pipeline is monitored using Prometheus and Grafana.
6. **Containerization**: The pipeline is containerized using Docker.
7. **Orchestration**: The pipeline is orchestrated using Kubernetes.
8. **Configuration Management**: The configuration is managed using Consul.

The pre-trained model can be found [here](./model).

If you want to train the model again, you can follow the [train_model.ipynb](./model/model_training.ipynb) notebook.

In the same directory as the notebook, you will find the [data](./model/data) directory which contains the dataset used to train the model. You will a data collection script [collect_data.py](./model/data/collect_data.py) which can be used to collect data from the Reddit API.

## Installation

In this section we will go through the installation of the project. You'll find instructions on how to install the project on your local machine and how to deploy it on a Kubernetes cluster. In this example we will use Minikube to deploy the project on a local Kubernetes cluster.

## 1. Install Minikube & kubectl
First, ensure that Minikube (for local Kubernetes) and kubectl (Kubernetes CLI) are installed. If you haven’t installed them yet, follow these steps:
```
# Linux
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube

# macOS
brew install minikube

# Windows (via Chocolatey)
choco install minikube
```
Install kubectl (if not installed)
```
# Linux/macOS
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
sudo mv kubectl /usr/local/bin/

# Windows (via Chocolatey)
choco install kubernetes-cli
```
Start Minikube
```
minikube start --memory=4g --cpus=2
```

### 1.2 Install Kafka

#### Step 1: Create a Kubernetes Namespace for the deployment of the pipeline
```
kubectl create namespace reddit-realtime
```
#### Step 2: Install Strimzi Kafka Operator
```
kubectl apply -f https://strimzi.io/install/latest?namespace=reddit-realtime -n reddit-realtime
```
#### Step 3: Check if the pods are running
```
kubectl get pods -n reddit-realtime
```
You should see the following pods running:
```
NAME                                       READY   STATUS    RESTARTS   AGE
strimzi-cluster-operator-xxxxxxx-xxxxx     1/1     Running   0          30s
```

### 1.3 Deploy a Kafka Cluster using Strimzi

#### Step 1: Create a Kafka Cluster
```
kubectl apply -f kafka_cluster.yaml
```

#### Step 2: Deploy the Kafka topics

```
kubectl apply -f incoming_topic.yaml
kubectl apply -f outgoing_topic.yaml
```

### 1.4 Install Spark

#### Step 1: Deploy Spark Operator

```
helm repo add spark-operator https://kubeflow.github.io/spark-operator
helm repo update
helm install spark-operator spark-operator/spark-operator --namespace spark-operator --create-namespace --wait
```

#### Step 2: Check if the pods are running
```
kubectl get pods -n spark-operator
```
Expected output:
```
NAME                                      READY   STATUS    RESTARTS   AGE
spark-operator-xxxxxxx-xxxxx              1/1     Running   0          30s
```

Once the pods are running, you can deploy the Spark application.

### Step 3: Deploy the Spark Application

```
kubectl apply -f spark_application.yaml
```

This Spark application will read from the Kafka topic, apply the model to the data and write the results to another Kafka topic. It uses a Spark Structured Streaming job to process the data in real-time. If you look at the [spark_application.yaml](./runtime/spark/spark_inference_application.yaml) file, you will see that it specifies the Docker image to use for the Spark application. This Docker image is built using the [Dockerfile](./runtime/spark/Dockerfile) in the same directory.

The commands used to build the Docker image and push it to Docker Hub are as follows:

```
docker build -t quay.io/carlesarnal/spark-inference:latest ./spark_reddit_fetcher/
docker push quay.io/carlesarnal/spark-inference:latest
```

You'll need to change the repository details in the commands above to match your own repository and you'll also need to change the Docker image in the [spark_application.yaml](./runtime/spark/spark_inference_application.yaml) file to the one you just built.

