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

Model BZK09 Section 2.1 as a reusable solver ladder: local Gauss-Seidel seeded from the just-rounded variable's dependency row, then warm-started conjugate gradient, then direct sparse fallback.

## Evidence

BZK09 describes pushing `nonzero(Ai)` after rounding `xi`, locally reducing residuals, and only escalating to CG/direct when local updates do not converge. The new `AdaptiveSolver` class captures this ladder without modifying the older `vectorfield.solver.BzkAdaptiveSolver`.

## Reuse Trigger

Use this when wiring mixed-integer rounding for cross fields, seamless parametrization, or any quad layout stage where one variable is fixed and the remaining least-squares minimizer needs a cheap warm-started update.

## Anti-pattern

Do not globally rebuild and solve the full reduced system after every rounded integer variable. It matches the math but misses BZK09's key performance idea and can time out at rocker-arm scale.
