#!/usr/bin/env bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="${SCRIPT_DIR}/helm"
RELEASE_NAME="xtdb-benchmark"
NAMESPACE="cloud-benchmark"

VALID_CLOUDS=("aws" "azure" "google-cloud")
VALID_BENCH_TYPES=("tpch" "readings" "auctionmark")

CLOUD=$1
BENCH_TYPE=$2
shift 2 || {
  echo "Usage: $0 <aws|azure|google-cloud> <tpch|readings|auctionmark> [--<key> <value> ...] [--no-cleanup]"
  exit 1
}

# Validate cloud
if [[ ! " ${VALID_CLOUDS[*]} " =~ " ${CLOUD} " ]]; then
  echo "Error: Invalid cloud '${CLOUD}'. Must be one of: ${VALID_CLOUDS[*]}"
  exit 1
fi

# Validate bench type
if [[ ! " ${VALID_BENCH_TYPES[*]} " =~ " ${BENCH_TYPE} " ]]; then
  echo "Error: Invalid benchType '${BENCH_TYPE}'. Must be one of: ${VALID_BENCH_TYPES[*]}"
  exit 1
fi

VALUES_FILE="${SCRIPT_DIR}/${CLOUD}/values.yaml"
[[ -f "$VALUES_FILE" ]] || {
  echo "Error: Cloud-specific values file not found: $VALUES_FILE"
  exit 1
}

NO_CLEANUP=false
HELM_ARGS=()
PENDING_KEY=""

# Helper: Convert kebab-case to camelCase
to_camel_case() {
  local input="$1"
  local result=""
  IFS='-' read -ra parts <<< "$input"
  for i in "${!parts[@]}"; do
    if [[ $i -eq 0 ]]; then
      result+="${parts[i]}"
    else
      result+="${parts[i]^}"
    fi
  done
  echo "$result"
}

# Parse remaining args
for arg in "$@"; do
  if [[ -n "$PENDING_KEY" ]]; then
    KEY_CAMEL=$(to_camel_case "$PENDING_KEY")
    HELM_ARGS+=("--set" "${BENCH_TYPE}.${KEY_CAMEL}=${arg}")
    PENDING_KEY=""
    continue
  fi

  if [[ "$arg" == "--no-cleanup" ]]; then
    NO_CLEANUP=true
  elif [[ "$arg" == --*=* ]]; then
    HELM_ARGS+=("$arg")
  elif [[ "$arg" == --* ]]; then
    PENDING_KEY="${arg#--}"
  else
    echo "Error: Unexpected argument '$arg'"
    exit 1
  fi
done

[[ -n "$PENDING_KEY" ]] && {
  echo "Error: No value provided for argument --$PENDING_KEY"
  exit 1
}

# Cloud-specific logic to fetch dynamic values from Terraform
case "$CLOUD" in
  azure)
    echo "Fetching Azure client ID from Terraform..."
    CLIENT_ID=$(terraform -chdir="cloud/${CLOUD}" output -raw user_assigned_identity_client_id)
    [[ -z "$CLIENT_ID" ]] && {
      echo "Error: CLIENT_ID could not be retrieved"
      exit 1
    }
    echo "Using Azure client ID: $CLIENT_ID"
    HELM_ARGS+=(
      --set providerConfig.env.AZURE_USER_MANAGED_IDENTITY_CLIENT_ID="$CLIENT_ID"
      --set providerConfig.serviceAccountAnnotations."azure\.workload\.identity/client-id"="$CLIENT_ID"
    )
    ;;
  aws)
    echo "Fetching AWS role ARN from Terraform..."
    SERVICE_ACCOUNT_ROLE_ARN=$(terraform -chdir="cloud/${CLOUD}" output -raw service_account_role_arn)
    [[ -z "$SERVICE_ACCOUNT_ROLE_ARN" ]] && {
      echo "Error: SERVICE_ACCOUNT_ROLE_ARN could not be retrieved"
      exit 1
    }
    echo "Using AWS role ARN: $SERVICE_ACCOUNT_ROLE_ARN"
    HELM_ARGS+=(
      --set providerConfig.serviceAccountAnnotations."eks\.amazonaws\.com/role-arn"="$SERVICE_ACCOUNT_ROLE_ARN"
    )
    ;;
  google-cloud)
    echo "Fetching Google Cloud service account from Terraform..."
    SERVICE_ACCOUNT_EMAIL=$(terraform -chdir="cloud/${CLOUD}" output -raw service_account_email)
    [[ -z "$SERVICE_ACCOUNT_EMAIL" ]] && {
      echo "Error: SERVICE_ACCOUNT_EMAIL could not be retrieved"
      exit 1
    }
    echo "Using GCP service account: $SERVICE_ACCOUNT_EMAIL"
    HELM_ARGS+=(
      --set providerConfig.serviceAccountAnnotations."iam\.gke\.io/gcp-service-account"="$SERVICE_ACCOUNT_EMAIL"
    )
    ;;
esac

# Optional cleanup
if ! $NO_CLEANUP; then
  echo "Clearing up previous benchmark run..."
  "${SCRIPT_DIR}/clear-bench.sh" "$CLOUD" || true
fi

# Ensure Helm dependencies are up to date
echo "Updating Helm dependencies..."
helm dependency update "$CHART_DIR"

# Deploy with Helm
echo "Deploying benchmark: cloud=$CLOUD, benchType=$BENCH_TYPE..."
helm upgrade --install "$RELEASE_NAME" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  -f "$VALUES_FILE" \
  --set benchType="$BENCH_TYPE" \
  "${HELM_ARGS[@]}"
