#!/usr/bin/env bash
# Sweep BZK09 §2.1 inner-solver tolerances on the hand mesh.
# Per the paper: localTol "e.g. 1e-6", cgTol unspecified (no tighter than localTol).
set -uo pipefail

cd "$(dirname "$0")/.."

echo "localTol,cgTol,bzkOrdered,count,agree,energy,maxResid,localGS,cg,direct,localIters,cgIters,wallSec"

for ltol in 1e-4 1e-5 1e-6 1e-7; do
  for ctol in 1e-3 1e-5 1e-7; do
    out=$(mvn surefire:test \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dtest='CrossFieldBuildProfileTest' \
      -DcrossField.localTol="$ltol" \
      -DcrossField.cgTol="$ctol" \
      -q 2>&1)
    count=$(echo "$out" | awk -F'actual=' '/singularities expected/ {split($2, a, " "); print a[1]; exit}')
    agree=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^agree=/){split($i,a,"="); print a[2]; exit}}')
    energy=$(echo "$out" | awk -F'smoothEnergy=' '/smoothEnergy=/ {split($2, a, " "); print a[1]; exit}')
    maxResid=$(echo "$out" | awk -F'maxResidual=' '/smoothEnergy=/ {split($2, a, " "); print a[1]; exit}')
    wall=$(echo "$out" | awk -F'wall time: ' '/wall time/ {split($2, a, "s"); print a[1]; exit}')
    localGS=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^localGS=/){split($i,a,"="); print a[2]; exit}}')
    cgConv=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^cg=/){split($i,a,"="); print a[2]; exit}}')
    direct=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^direct=/){split($i,a,"="); print a[2]; exit}}')
    localIters=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^localIters=/){split($i,a,"="); print a[2]; exit}}')
    cgIters=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^cgIters=/){split($i,a,"="); print a[2]; exit}}')
    bzkOk=$(awk -v l="$ltol" -v c="$ctol" 'BEGIN{print (c+0 >= l+0) ? "yes" : "no"}')
    echo "$ltol,$ctol,$bzkOk,${count:-NA},${agree:-NA},${energy:-NA},${maxResid:-NA},${localGS:-NA},${cgConv:-NA},${direct:-NA},${localIters:-NA},${cgIters:-NA},${wall:-NA}"
  done
done
