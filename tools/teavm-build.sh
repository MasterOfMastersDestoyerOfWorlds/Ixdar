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
# The script fails loudly rather than emitting a broken bundle:
#   * the annotations module is installed into ~/.m2 before the build, because
#     maven-compiler-plugin resolves the annotationProcessorPaths entry
#     IXDAR:annotations:0.0.1 from the repository and never from the reactor, so
#     -am alone leaves a fresh worktree running whatever stale processor jar was
#     installed last;
#   * every [ERROR] line is reprinted after the build output, where a tail finds
#     it, instead of being buried thousands of lines up;
#   * a missing or stub classes.js exits non-zero (TeaVM writes a ~36-byte stub
#     and lets Maven succeed when a method it cannot compile is reachable);
#   * the run ends with one summary line: verdict, classes.js size, [ERROR]
#     count, seconds.
#
# Usage:
#   ./tools/teavm-build.sh              # package TeaVM JS output
#   ./tools/teavm-build.sh --hugo       # package + hugo -D in KriegEterna
#
# There is no `ixdar-cli build --web` yet (TOOL-2); when it lands it should shell
# out to this script rather than run Maven itself. The same applies to
# ixdar_automation_cli/cli_commands/rebuild_krieg_web.py, which runs its own
# `mvn package -P web-teavm` and so gets none of the checks below.
#
set -euo pipefail
cd "$(dirname "$0")/.."

START_SECONDS=$SECONDS

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

CLASSES_JS="ixdar-app/target/teavm/ixdar/classes.js"
MIN_CLASSES_JS_BYTES=100000
BUILD_LOG="ixdar-app/target/teavm-build.log"
mkdir -p "$(dirname "$BUILD_LOG")"
: > "$BUILD_LOG"

# Yesterday's bundle must not be mistaken for today's: without this a failing
# build leaves the last good classes.js in place and passes the size check.
rm -f "$CLASSES_JS"

# Runs a command, showing its output and appending it to the build log, and
# returns the command's own exit status rather than tee's.
run_logged() {
    "$@" 2>&1 | tee -a "$BUILD_LOG"
    return "${PIPESTATUS[0]}"
}

# --- Build ---------------------------------------------------------------------
echo "[teavm-build] Installing IXDAR:annotations into ~/.m2 (annotation processor path)"
BUILD_STATUS=0
run_logged mvn -q install -pl annotations -DskipTests || BUILD_STATUS=$?

if (( BUILD_STATUS == 0 )); then
    run_logged mvn package -pl ixdar-app -am -P web-teavm -DskipTests \
        ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"} || BUILD_STATUS=$?
else
    echo "[teavm-build] annotations install failed; skipping the web build"
fi

# --- Errors, repeated where a tail sees them -----------------------------------
ERROR_LINES="$(grep -F '[ERROR]' "$BUILD_LOG" || true)"
ERROR_COUNT=0
if [[ -n "$ERROR_LINES" ]]; then
    ERROR_COUNT="$(printf '%s\n' "$ERROR_LINES" | wc -l)"
    echo
    echo "[teavm-build] $ERROR_COUNT [ERROR] line(s), repeated from the build output:"
    printf '%s\n' "$ERROR_LINES"
    echo
fi

# --- Verdict and summary -------------------------------------------------------
CLASSES_JS_BYTES=0
if [[ -f "$CLASSES_JS" ]]; then
    CLASSES_JS_BYTES="$(stat -c %s "$CLASSES_JS")"
fi

VERDICT=OK
if (( BUILD_STATUS != 0 )); then
    VERDICT="FAILED (maven exit $BUILD_STATUS)"
elif (( CLASSES_JS_BYTES < MIN_CLASSES_JS_BYTES )); then
    VERDICT="FAILED (classes.js is missing or a $CLASSES_JS_BYTES-byte stub, under the"
    VERDICT="$VERDICT $MIN_CLASSES_JS_BYTES-byte minimum; the [ERROR] lines above say which"
    VERDICT="$VERDICT method TeaVM could not compile)"
fi

echo "[teavm-build] $VERDICT: $CLASSES_JS $CLASSES_JS_BYTES bytes," \
     "$ERROR_COUNT [ERROR] line(s), $(( SECONDS - START_SECONDS ))s, full log $BUILD_LOG"

if [[ "$VERDICT" != OK ]]; then
    exit 1
fi

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
