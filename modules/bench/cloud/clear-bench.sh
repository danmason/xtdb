#!/usr/bin/env bash

set -e

CLOUD=$1
[[ -z "$CLOUD" ]] && {
  echo "Usage: $0 <aws|azure|google-cloud>"
  exit 1
}

RELEASE_NAME="xtdb-benchmark"
NAMESPACE="cloud-benchmark"

echo "Clearing previous Helm release..."
helm uninstall "$RELEASE_NAME" --namespace "$NAMESPACE" || true
echo "Previous Helm release cleared."

echo "Clearing storage for '$CLOUD'..."

case "$CLOUD" in
  azure)
    echo "Clearing Azure Blob container: xtdbazurebenchmarkcontainer"
    az storage blob delete-batch \
      --account-name xtdbazurebenchmark \
      --source xtdbazurebenchmarkcontainer
    ;;
  aws)
    echo "Emptying S3 bucket: xtdb-bench-bucket"
    aws s3 --profile xtdb-bench rm s3://xtdb-bench-bucket --recursive
    ;;
  google-cloud)
    echo "Clearing Google Cloud Storage bucket: xtdb-gcp-benchmark-bucket"
    gcloud storage rm gs://xtdb-gcp-benchmark-bucket/** || true
    ;;
  *)
    echo "Unknown cloud provider: $CLOUD"
    exit 1
    ;;
esac

echo "Storage cleared for '$CLOUD'"
