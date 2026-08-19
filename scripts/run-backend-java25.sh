#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
JAVA_HOME="/usr/local/opt/openjdk@25"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "[ERROR] OpenJDK 25 was not found at $JAVA_HOME" >&2
  exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT_DIR"
echo "Using Java:"
java -version
echo
echo "Starting backend with Gradle bootRun..."
exec ./gradlew --no-daemon :app:bootRun
