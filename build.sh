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

# Card-name lint (0.17.0): a Java string literal equal to a Forge card name
# fails the build (README, "Boundary rule"). It needs Python 3 and Forge's
# cardsfolder, read at build time and never copied. Where either is missing
# (the worker image's JDK builder has no Python) it is skipped with a
# warning; REQUIRE_CARD_LINT=1 turns a skip into a failure.
PY=""
for cand in python3 python py; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import sys; sys.exit(sys.version_info < (3, 8))' >/dev/null 2>&1; then
    PY="$cand"; break
  fi
done
CARDS="${FORGE_CARDSFOLDER:-$(dirname "$JAR")/res/cardsfolder}"
if [[ -n "$PY" && -e "$CARDS" ]]; then
  "$PY" tools/lint_card_names.py --cardsfolder "$CARDS"
elif [[ "${REQUIRE_CARD_LINT:-0}" == "1" ]]; then
  echo "card-name lint required but cannot run (python: ${PY:-none}, cardsfolder: $CARDS)" >&2
  exit 1
else
  echo "warning: card-name lint skipped (python: ${PY:-none}, cardsfolder: $CARDS)" >&2
fi

rm -rf out && mkdir -p out
javac -encoding UTF-8 -cp "$JAR" -d out $(find src -name '*.java')
# Provenance (0.17.0): the commit this jar was compiled from, echoed in every
# run header as meta.shimCommit. "-dirty" when src/ differs from it.
COMMIT="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
if [[ "$COMMIT" != "unknown" && -n "$(git status --porcelain -- src 2>/dev/null)" ]]; then
  COMMIT="$COMMIT-dirty"
fi
printf '%s\n' "$COMMIT" > out/simlab/shim/BUILD_COMMIT
jar cf simlab-forge-shim.jar -C out .
echo "built: simlab-forge-shim.jar ($COMMIT)"
