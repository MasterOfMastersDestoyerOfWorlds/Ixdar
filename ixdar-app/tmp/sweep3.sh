#!/usr/bin/env bash
# Sweep radius series shape (r0Mul, ratio, hFraction) with bestFace mode + tau=0.85.
# Ratio=100 collapses to single-radius mode (matches libigl style).
set -uo pipefail

cd "$(dirname "$0")/.."

echo "h,r0Mul,rRatio,tauMin,allFaces,count,agree,refOnly,genOnly,energy,wallSec"

for h in 0.04 0.06 0.08 0.10; do
  for r0 in 1.0 2.0 3.0 5.0; do
    for ratio in 1.2 1.414 2.0 100.0; do
      for tau in 0.8 0.85 0.9; do
        out=$(mvn surefire:test \
          -Dsurefire.failIfNoSpecifiedTests=false \
          -Dtest='CrossFieldBuildProfileTest' \
          -DcrossField.hFraction="$h" \
          -DcrossField.tauMin="$tau" \
          -DcrossField.kScale=0.1 \
          -DcrossField.allFaces=false \
          -DcrossField.r0Mul="$r0" \
          -DcrossField.rRatio="$ratio" \
          -q 2>&1)
        count=$(echo "$out" | awk -F'actual=' '/singularities expected/ {split($2, a, " "); print a[1]; exit}')
        agree=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^agree=/){split($i,a,"="); print a[2]; exit}}')
        refOnly=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^refOnly=/){split($i,a,"="); print a[2]; exit}}')
        genOnly=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^genOnly=/){split($i,a,"="); print a[2]; exit}}')
        energy=$(echo "$out" | awk -F'smoothEnergy=' '/smoothEnergy=/ {split($2, a, " "); print a[1]; exit}')
        wall=$(echo "$out" | awk -F'wall time: ' '/wall time/ {split($2, a, "s"); print a[1]; exit}')
        echo "$h,$r0,$ratio,$tau,false,${count:-NA},${agree:-NA},${refOnly:-NA},${genOnly:-NA},${energy:-NA},${wall:-NA}"
      done
    done
  done
done
