#!/usr/bin/env bash
# Refined sweep: include both allFaces=true and allFaces=false; tighter tauMin grid.
set -uo pipefail

cd "$(dirname "$0")/.."

echo "h,tauMin,kScale,allFaces,count,agree,refOnly,genOnly,energy,wallSec"

for allFaces in true false; do
  for h in 0.025 0.03 0.04 0.05 0.06 0.08; do
    for tau in 0.7 0.8 0.85 0.9 0.93 0.95; do
      out=$(mvn surefire:test \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dtest='CrossFieldBuildProfileTest' \
        -DcrossField.hFraction="$h" \
        -DcrossField.tauMin="$tau" \
        -DcrossField.kScale=0.1 \
        -DcrossField.allFaces="$allFaces" \
        -q 2>&1)
      count=$(echo "$out" | awk -F'actual=' '/singularities expected/ {split($2, a, " "); print a[1]; exit}')
      agree=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^agree=/){split($i,a,"="); print a[2]; exit}}')
      refOnly=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^refOnly=/){split($i,a,"="); print a[2]; exit}}')
      genOnly=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^genOnly=/){split($i,a,"="); print a[2]; exit}}')
      energy=$(echo "$out" | awk -F'smoothEnergy=' '/smoothEnergy=/ {split($2, a, " "); print a[1]; exit}')
      wall=$(echo "$out" | awk -F'wall time: ' '/wall time/ {split($2, a, "s"); print a[1]; exit}')
      echo "$h,$tau,0.1,$allFaces,${count:-NA},${agree:-NA},${refOnly:-NA},${genOnly:-NA},${energy:-NA},${wall:-NA}"
    done
  done
done
