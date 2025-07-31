#!/usr/bin/env bash

CLOUD=$1
if [ -z "$CLOUD" ]; then
  echo "Usage: $0 <aws|azure|google-cloud>"
  exit 1
fi 

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

set -e
(
  cd "$SCRIPT_DIR/$CLOUD"
  echo "Setting up infrastructure for $CLOUD"
  terraform init
  terraform apply
  echo "Infrastructure setup complete for $CLOUD"

  echo "Connecting kubectl to the xtdb-bench-cluster Kubernetes cluster..."
  if [ "$CLOUD" == "aws" ]; then
    aws eks --profile xtdb-bench --region us-east-1 update-kubeconfig --name xtdb-bench-cluster
  elif [ "$CLOUD" == "azure" ]; then
    az aks get-credentials --resource-group cloud-benchmark-resources --name xtdb-bench-cluster
  elif [ "$CLOUD" == "google-cloud" ]; then
    cloud container clusters get-credentials xtdb-bench-cluster --region us-central1
  else
    echo "Unknown cloud provider: $CLOUD"
    exit 1
  fi
  echo "Kubernetes context set to xtdb-bench-cluster cluster in $CLOUD"
)
