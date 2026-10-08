#!/usr/bin/env bash
# Gradle on a flaky connection: DNS blips fail a build, but everything already downloaded is kept,
# so retrying converges. Only retries when the failure looks like a download problem; a real
# compile error stops immediately. Usage: ./build-with-retry.sh <gradle args...>
set -o pipefail
cd "$(dirname "$0")"

# A developer may point at a private environment file, but the repository must also build without it.
if [ -n "${KEPT_DEV_ENV:-}" ] && [ -f "$KEPT_DEV_ENV" ]; then
  # shellcheck disable=SC1090
  . "$KEPT_DEV_ENV"
fi

# Android tooling accepts either variable. Find common SDK locations when neither is configured.
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  for sdk in "$HOME/Android/Sdk" /opt/android-sdk /usr/local/android-sdk; do
    if [ -d "$sdk" ]; then
      export ANDROID_SDK_ROOT="$sdk"
      export ANDROID_HOME="$sdk"
      break
    fi
  done
fi

if [ -f ./gradlew ]; then
  gradle_cmd=(bash ./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  gradle_cmd=(gradle)
else
  echo "Gradle is unavailable: keep android/gradlew in the checkout or install Gradle." >&2
  exit 1
fi

for attempt in 1 2 3 4 5 6 7 8; do
  echo "== attempt $attempt: ${gradle_cmd[*]} $*"
  out=$(mktemp)
  "${gradle_cmd[@]}" "$@" --console=plain --no-daemon 2>&1 | tee "$out"
  status=${PIPESTATUS[0]}
  [ "$status" = "0" ] && { rm -f "$out"; exit 0; }
  if grep -qE "Could not (GET|resolve|download|get resource)|Read timed out|Connection (reset|refused)|UnknownHost|Premature end|Failed to transform" "$out" && ! grep -qE "^e: |error: |FAILED.*compile|Execution failed for task ':app:(compile|test)[A-Za-z]*Kotlin" "$out"; then
    echo "== attempt $attempt failed on the network, retrying"; rm -f "$out"; continue
  fi
  echo "== attempt $attempt failed for a non-network reason, stopping"; rm -f "$out"; exit 1
done
exit 1
