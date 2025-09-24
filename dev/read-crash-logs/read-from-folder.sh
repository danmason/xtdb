#!/usr/bin/env bash
set -euo pipefail

# Takes in a crash log directory, then reads the individual files
# and writes them into a subfolder (EDN format) for easier consumption.
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <crash-log-dir>"
  exit 1
fi

# Resolve script dir (absolute path)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRASH_LOG_DIR="$(cd "$1" && pwd)"

# Project root is two levels up from script dir
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ensure edn output dir exists
mkdir -p "$CRASH_LOG_DIR/edn"

cd "$PROJECT_ROOT"

# Run gradle tasks
cp "$CRASH_LOG_DIR"/crash.edn "$CRASH_LOG_DIR/edn/" || true
./gradlew readArrowFile -Pfile="$CRASH_LOG_DIR/query-rel.arrow" -PoutputFile="$CRASH_LOG_DIR/edn/query-rel.edn"
./gradlew readArrowFile -Pfile="$CRASH_LOG_DIR/tx-ops.arrow" -PoutputFile="$CRASH_LOG_DIR/edn/tx-ops.edn"
./gradlew readArrowFile -Pfile="$CRASH_LOG_DIR/live-table-tx.arrow" -PoutputFile="$CRASH_LOG_DIR/edn/live-table-tx.edn"
./gradlew readHashTrieFile -Pfile="$CRASH_LOG_DIR/live-trie-tx.binpb" -PoutputFile="$CRASH_LOG_DIR/edn/live-trie-tx.edn"
./gradlew readHashTrieFile -Pfile="$CRASH_LOG_DIR/live-trie.binpb" -PoutputFile="$CRASH_LOG_DIR/edn/live-trie.edn"
