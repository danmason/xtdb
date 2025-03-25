#!/bin/bash
set -e

echo "Uninstalling XTDB, Kafka, and MinIO..."
helm uninstall xtdb-minio || true
helm uninstall kafka || true
helm uninstall minio || true

if [[ $1 == "--destroy-minikube" ]]; then
  echo "Stopping Minikube..."
  minikube stop

  echo "Deleting Minikube..."
  minikube delete
else
  echo "Skipping Minikube destruction."
fi

echo "Done cleaning up!"
