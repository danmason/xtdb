#!/usr/bin/env bash

set -e

# Get the directory where the script resides (absolute path)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CLOUD=$1
if [[ -z "$CLOUD" ]]; then
  echo "Usage: $0 <cloud> [--set key=value ...]"
  echo "Example: $0 azure --set benchType=Auctionmark --set auctionmark.scaleFactor=1"
  exit 1
fi

shift # Shift arguments so "$@" contains only optional overrides
CHART_DIR="${SCRIPT_DIR}/helm"
RELEASE_NAME="xtdb-benchmark"
NAMESPACE="cloud-benchmark"

VALUES_FILE="${SCRIPT_DIR}/${CLOUD}/values.yaml"
if [[ ! -f "$VALUES_FILE" ]]; then
  echo "Error: Cloud-specific values file not found: $VALUES_FILE"
  exit 1
fi

# Start Helm command
CMD=(
  helm install "$RELEASE_NAME" "$CHART_DIR"
  --namespace "$NAMESPACE"
  --create-namespace
  -f "$VALUES_FILE"
)

# Azure-specific logic
if [[ "$CLOUD" == "azure" ]]; then
  echo "Fetching Azure user-assigned managed identity client ID from Terraform..."

  CLIENT_ID=$(terraform -chdir="cloud/${CLOUD}" output -raw user_assigned_identity_client_id)

  if [[ -z "$CLIENT_ID" ]]; then
    echo "Error: CLIENT_ID could not be retrieved from Terraform output"
    exit 1
  fi

  echo "Using Azure client ID: $CLIENT_ID"

  CMD+=(
    --set providerConfig.env.XTDB_AZURE_USER_MANAGED_IDENTITY_CLIENT_ID="$CLIENT_ID"
    --set providerConfig.serviceAccountAnnotations."azure\.workload\.identity/client-id"="$CLIENT_ID"
  )
fi

# Append user-provided --set overrides (if any)
CMD+=("$@")

echo "Clearing previous Helm release..."
helm uninstall "$RELEASE_NAME" --namespace "$NAMESPACE" || true

# Run Helm
echo "Running: ${CMD[*]}"
"${CMD[@]}"
