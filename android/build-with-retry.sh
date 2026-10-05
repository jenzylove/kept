#!/bin/bash
# Gradle on a flaky connection: DNS blips fail a build, but everything already downloaded is kept,
# so retrying converges. Only retries when the failure looks like a download problem; a real
# compile error stops immediately. Usage: ./build-with-retry.sh <gradle args...>
. /c/Users/LENOVO/dev-tools/env.sh
cd "$(dirname "$0")"
for attempt in 1 2 3 4 5 6 7 8; do
  echo "== attempt $attempt: gradle $*"
  out=$(mktemp)
  gradle "$@" --console=plain --no-daemon 2>&1 | tee "$out"
  status=${PIPESTATUS[0]}
  [ "$status" = "0" ] && { rm -f "$out"; exit 0; }
  if grep -qE "Could not (GET|resolve|download|get resource)|Read timed out|Connection (reset|refused)|UnknownHost|Premature end|Failed to transform" "$out" && ! grep -qE "^e: |error: |FAILED.*compile|Execution failed for task ':app:(compile|test)[A-Za-z]*Kotlin" "$out"; then
    echo "== attempt $attempt failed on the network, retrying"; rm -f "$out"; continue
  fi
  echo "== attempt $attempt failed for a non-network reason, stopping"; rm -f "$out"; exit 1
done
exit 1
