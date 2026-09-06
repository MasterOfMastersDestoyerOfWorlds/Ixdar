#!/usr/bin/env bash
# Package the TeaVM JavaScript output for the Krieg Eterna web build.
#
# TeaVM 0.15 bundles an ASM that reads class files up to Java 25 (major 69), so
# the build must run on a JDK 25 even though the code targets release 25 and the
# desktop toolchain may be newer. JDK selection, first match wins:
#   1. $TEAVM_JAVA_HOME
#   2. `mise where java@25` (install with `mise install java@25`)
#   3. whatever JDK Maven already uses (warns if newer than 25)
#
# Usage:
#   ./tools/teavm-build.sh              # package TeaVM JS output
#   ./tools/teavm-build.sh --hugo       # package + hugo -D in KriegEterna
#
set -euo pipefail
cd "$(dirname "$0")/.."

# --- JDK selection --------------------------------------------------------------
TEAVM_JDK="${TEAVM_JAVA_HOME:-}"
if [[ -z "$TEAVM_JDK" ]] && command -v mise >/dev/null 2>&1; then
    TEAVM_JDK="$(mise where java@25 2>/dev/null || true)"
fi
if [[ -n "$TEAVM_JDK" && -x "$TEAVM_JDK/bin/java" ]]; then
    export JAVA_HOME="$TEAVM_JDK"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "[teavm-build] Using JDK at $JAVA_HOME"
else
    JAVA_MAJOR="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')"
    if [[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -gt 25 ]]; then
        echo "[teavm-build] WARNING: JDK $JAVA_MAJOR is newer than TeaVM 0.15 can read;" \
             "set TEAVM_JAVA_HOME or run 'mise install java@25'" >&2
    fi
fi

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
# -am builds the annotations module in the same reactor instead of resolving a
# possibly stale jar from ~/.m2.
mvn package -pl ixdar-app -am -P web-teavm -DskipTests ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}

# TeaVM reports "[ERROR] Method ... was not found" yet still emits a ~36-byte
# classes.js stub and lets Maven succeed. Treat a stub as a failed build.
CLASSES_JS="ixdar-app/target/teavm/ixdar/classes.js"
MIN_CLASSES_JS_BYTES=100000
if [[ ! -s "$CLASSES_JS" ]] || (( $(stat -c %s "$CLASSES_JS") < MIN_CLASSES_JS_BYTES )); then
    echo "[teavm-build] FAILED: $CLASSES_JS is missing or a stub; see the [ERROR] lines above" >&2
    exit 1
fi

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
