#!/bin/bash
set -e

if [[ $1 == "--start-minikube"]]; then
  echo "Starting Minikube..."
  minikube start
else
  echo "Didn't start Minikube - include --start-minikube to start Minikube..."
fi

echo "Setting kubectl context to Minikube..."
kubectl config use-context minikube

echo "Creating namespace for XTDB deployment..."
kubectl create namespace xtdb-deployment

echo "Adding Bitnami Helm repo..."
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

echo "Deploying MinIO..."
helm install minio bitnami/minio --namespace xtdb-deployment \
  --set auth.rootUser=minioadmin \
  --set auth.rootPassword=minioadmin \
  --set defaultBuckets=xtdb

echo "Deploying Kafka..."
helm install kafka bitnami/kafka --namespace xtdb-deployment \
  --set replicaCount=1 \
  --set zookeeper.replicaCount=1 \
  --set listeners.client.protocol=PLAINTEXT

echo "Deploying XTDB via Helm..."
helm install xtdb-minio helm --namespace xtdb-deployment

echo "✅ Deployment complete!"
