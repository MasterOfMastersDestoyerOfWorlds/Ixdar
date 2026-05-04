---
title: BZK Adaptive Solver Ladder
category: architecture
severity: medium
modules: [quadlayout]
tags: [BZK09, adaptive-solver, mixed-integer, gauss-seidel, conjugate-gradient, quad-layout]
---

# BZK Adaptive Solver Ladder

## Context

The quad layout cross-field rewrite reached BZK09-like rocker-arm problem dimensions but timed out with a naive global re-solve after each rounded integer variable.

## Decision

Model BZK09 Section 2.1 as a reusable solver ladder: bootstrap once with a robust global solve, then use local Gauss-Seidel seeded from the just-rounded variable's immediate dependency patch, then warm-started conjugate gradient, then direct sparse fallback.

Use BZK09 greedy rounding as the correctness baseline for cross-field periods: round the smallest-roundoff integer, seed local Gauss-Seidel from `nonzero(Ai)`, then escalate to CG/direct only when the local update hits its cap. In this implementation, locality-aware batching is enabled by default only after validating it against the one-at-a-time baseline.

## Evidence

BZK09 describes pushing `nonzero(Ai)` after rounding `xi`, locally reducing residuals, and only escalating to CG/direct when local updates do not converge. The new `AdaptiveSolver` class captures this ladder without modifying the older `vectorfield.solver.BzkAdaptiveSolver`.

Rocker-arm debugging showed the system must keep BZK-sized unknowns: face angles plus chord-only integer variables. Eliminating tree/fixed periods into the right-hand side brought the rewrite to `fullDim=32985` and `int=12897`, close to BZK09's `Dim=32843` and `#Int=12064`. The 30-second watchdog still timed out at `rounded=9728`, but diagnostics clearly identified local GS cap hits as the next bottleneck instead of matrix dimension drift.

Default locality-aware batching with `roundBatchSize=8` and `roundBatchTol=1e-3` preserved the rollback path and reached `rounded=9472` before the watchdog, with `avgBatch=1.038`. A looser `roundBatchTol=1e-2` formed larger batches (`avgBatch=1.538`) but escalated more often to CG and reached only `rounded=8960`, so tolerances need residual/CG validation rather than simply increasing batch size.

Re-reading BZK09 corrected the baseline: the paper describes one-at-a-time greedy rounding, local GS tolerance such as `1e-6`, and seed queue `nonzero(Ai)`. With those defaults, rocker-arm reduction matched the paper invariant exactly (`expectedForest=12666`, `actualForest=12666`), but a low local GS cap timed out at `rounded=3328` because local GS escalated to CG too often. Increasing the local GS cap to `500000` let the benchmark complete within the watchdog in manual testing, so the default should favor enough local GS work before global fallback.

## Reuse Trigger

Use this when wiring mixed-integer rounding for cross fields, seamless parametrization, or any quad layout stage where one variable is fixed and the remaining least-squares minimizer needs a cheap warm-started update. For cross-field periods, build matrices over chord variables only and recover all signed edge jumps from final residuals.

When batching, use dependency-patch disjointness plus low roundoff as the admission rule. If batches stay near size one, inspect roundoff rejections before widening tolerance; if widening tolerance increases CG escalation, treat the batch as too aggressive. Keep `enableBatchRounding=false` available to compare against the one-at-a-time paper baseline.

## Anti-pattern

Do not globally rebuild and solve the full reduced system after every rounded integer variable. Also do not keep one integer variable for every mesh edge after the dual-tree reduction has identified fixed/tree periods; it inflates the matrix and obscures whether solver escalation is a math problem or a modeling problem.

Do not batch arbitrary best-ranked variables without checking local patch overlap, and do not assume a looser roundoff threshold is faster. It can create larger simultaneous perturbations that overwhelm local GS and shift cost into CG.
