#!/usr/bin/env bash
# Refine ladder caps: small cgMax forces fast escalation to direct;
# higher localMax avoids escalation entirely. Find the sweet spot.
set -uo pipefail

cd "$(dirname "$0")/.."

echo "localMax,cgMax,count,agree,refOnly,genOnly,energy,maxResid,wallSec,localGS,cg,direct,localIters"

for localMax in 50000 65000 80000 100000; do
  for cgMax in 10 25 35 50; do
    out=$(mvn surefire:test \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dtest='CrossFieldBuildProfileTest' \
      -DcrossField.localMax="$localMax" \
      -DcrossField.cgMax="$cgMax" \
      -q 2>&1)
    count=$(echo "$out" | awk -F'actual=' '/singularities expected/ {split($2, a, " "); print a[1]; exit}')
    agree=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^agree=/){split($i,a,"="); print a[2]; exit}}')
    refOnly=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^refOnly=/){split($i,a,"="); print a[2]; exit}}')
    genOnly=$(echo "$out" | awk '/singularity overlap/ {for(i=1;i<=NF;i++) if($i ~ /^genOnly=/){split($i,a,"="); print a[2]; exit}}')
    energy=$(echo "$out" | awk -F'smoothEnergy=' '/smoothEnergy=/ {split($2, a, " "); print a[1]; exit}')
    maxResid=$(echo "$out" | awk -F'maxResidual=' '/smoothEnergy=/ {split($2, a, " "); print a[1]; exit}')
    wall=$(echo "$out" | awk -F'wall time: ' '/wall time/ {split($2, a, "s"); print a[1]; exit}')
    localGS=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^localGS=/){split($i,a,"="); print a[2]; exit}}')
    cgConv=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^cg=/){split($i,a,"="); print a[2]; exit}}')
    direct=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^direct=/){split($i,a,"="); print a[2]; exit}}')
    localIters=$(echo "$out" | awk '/adaptive localGS=/ {for(i=1;i<=NF;i++) if($i ~ /^localIters=/){split($i,a,"="); print a[2]; exit}}')
    echo "$localMax,$cgMax,${count:-NA},${agree:-NA},${refOnly:-NA},${genOnly:-NA},${energy:-NA},${maxResid:-NA},${wall:-NA},${localGS:-NA},${cgConv:-NA},${direct:-NA},${localIters:-NA}"
  done
done
