---
title: BZK Cross Field Period Holonomy
category: architecture
severity: high
modules: [quadlayout]
tags: [BZK09, cross-field, period-jump, holonomy, singularity, quad-layout]
---

# BZK Cross Field Period Holonomy

## Context

While rewriting the quad layout cross-field stage, the rocker-arm benchmark reported 10018 singularities instead of the 36 singularities reported by LCK21/BZK-style input data.

## Decision

Keep BZK period jumps as signed integers throughout solving and singularity extraction. Recover output jumps from the final signed residual and include the signed transport-angle walk when computing vertex index.

## Evidence

BZK09 defines `p : E -> Z`, states `pij = -pji` and `kappa_ij = -kappa_ji`, and computes vertex index from angle defect, signed kappa walk, and signed period jumps. After applying this to `CrossField`, `mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.BenchmarkRockerArmLyon` completed successfully with 168 singularities in about 5.1s layout time, down from the previously reported 10018.

## Reuse Trigger

Use this whenever implementing or reviewing BZK09/Ray08 N-RoSy fields, seamless transition functions, singularity extraction, branch matching, or cross-field rendering.

## Anti-pattern

Do not store solver period jumps modulo 4 or use `{0,1,2,3}` branch IDs in holonomy math. Modulo reduction belongs only at the rendering/branch-selection boundary; using it during signed index computation creates fake singularities.
