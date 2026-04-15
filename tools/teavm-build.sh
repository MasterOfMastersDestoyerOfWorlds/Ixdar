#!/usr/bin/env bash
# Permanent fix: TeaVM 0.9.2 cannot read JDK 26 system classes (class file
# version 69).  Running Maven under JDK 21 produces version-65 class files
# that TeaVM can handle.
#
# Usage:
#   ./tools/teavm-build.sh              # package TeaVM JS output
#   ./tools/teavm-build.sh --hugo       # package + hugo -D in KriegEterna
#
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Resolve JDK 21 -----------------------------------------------------------
if [[ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]]; then
    JDK21="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
elif command -v /usr/libexec/java_home &>/dev/null; then
    JDK21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi

if [[ -z "${JDK21:-}" || ! -d "${JDK21}" ]]; then
    echo "ERROR: JDK 21 not found.  Install it:  brew install openjdk@21" >&2
    exit 1
fi

echo "[teavm-build] Using JAVA_HOME=$JDK21"
export JAVA_HOME="$JDK21"
export PATH="$JAVA_HOME/bin:$PATH"

# --- Build ---------------------------------------------------------------------
mvn package -pl ixdar-app -P web-teavm -DskipTests "$@"

echo "[teavm-build] TeaVM output: ixdar-app/target/teavm/ixdar/"

# --- Optional Hugo rebuild -----------------------------------------------------
if [[ "${1:-}" == "--hugo" ]]; then
    HUGO_DIR="$(dirname "$PWD")/KriegEterna/web"
    if [[ -d "$HUGO_DIR" ]]; then
        echo "[teavm-build] Running hugo -D in $HUGO_DIR"
        (cd "$HUGO_DIR" && hugo -D)
    else
        echo "[teavm-build] KriegEterna/web not found at $HUGO_DIR, skipping Hugo"
    fi
fi
