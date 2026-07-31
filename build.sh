#!/usr/bin/env bash
# Build simlab-forge-shim against a local Forge jar. No Maven needed.
set -euo pipefail
cd "$(dirname "$0")"

JAR="${FORGE_JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(ls "$HOME"/forge/forge-gui-desktop-*-jar-with-dependencies.jar 2>/dev/null | sort | tail -1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "Forge jar not found. Set FORGE_JAR." >&2
  exit 1
fi
echo "building against: $JAR"

rm -rf out && mkdir -p out
javac -encoding UTF-8 -cp "$JAR" -d out $(find src -name '*.java')
jar cf simlab-forge-shim.jar -C out .
echo "built: simlab-forge-shim.jar"
