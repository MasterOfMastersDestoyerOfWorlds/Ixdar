#!/usr/bin/env bash
# Point .profiler/libasyncProfiler at this platform's async-profiler library.
#
# The launch configs and run-scene load the agent from one fixed, extension-free path so a single
# setting works on every platform. -agentpath is fatal when the file is missing, so a fresh clone
# must run this once before any scene will start.

#TODO get rid of this garbage
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .profiler
for candidate in \
    /opt/homebrew/lib/libasyncProfiler.dylib \
    /usr/local/lib/libasyncProfiler.dylib \
    /usr/lib/libasyncProfiler.so \
    /usr/local/lib/libasyncProfiler.so; do
  if [ -f "$candidate" ]; then
    ln -sf "$candidate" .profiler/libasyncProfiler
    echo "linked .profiler/libasyncProfiler -> $candidate"
    exit 0
  fi
done
echo "async-profiler not found. macOS: brew install async-profiler." >&2
echo "Linux: install it and place libasyncProfiler.so in /usr/lib." >&2
exit 1
