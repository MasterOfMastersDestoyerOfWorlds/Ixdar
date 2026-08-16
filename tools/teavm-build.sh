#!/usr/bin/env bash
# Package the TeaVM JavaScript output for the Krieg Eterna web build.
#
# TeaVM 0.13 added Java 25 support, so this no longer needs a pinned JDK — it
# runs under whatever JDK Maven is already using.
#
# Usage:
#   ./tools/teavm-build.sh              # package TeaVM JS output
#   ./tools/teavm-build.sh --hugo       # package + hugo -D in KriegEterna
#
set -euo pipefail
cd "$(dirname "$0")/.."

MAVEN_ARGS=()
RUN_HUGO=false
for arg in "$@"; do
    if [[ "$arg" == "--hugo" ]]; then
        RUN_HUGO=true
    else
        MAVEN_ARGS+=("$arg")
    fi
done

# --- Build ---------------------------------------------------------------------
mvn package -pl ixdar-app -P web-teavm -DskipTests ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}

echo "[teavm-build] TeaVM output: ixdar-app/target/teavm/ixdar/"

# --- Optional Hugo rebuild -----------------------------------------------------
if [[ "$RUN_HUGO" == true ]]; then
    HUGO_DIR="$(dirname "$PWD")/KriegEterna/web"
    if [[ -d "$HUGO_DIR" ]]; then
        echo "[teavm-build] Running hugo -D in $HUGO_DIR"
        (cd "$HUGO_DIR" && hugo -D)
    else
        echo "[teavm-build] KriegEterna/web not found at $HUGO_DIR, skipping Hugo"
    fi
fi
