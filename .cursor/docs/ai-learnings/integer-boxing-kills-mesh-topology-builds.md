---
title: Integer boxing kills mesh topology builds
category: performance
severity: critical
modules: [geometry, mesh]
tags: [mesh, topology, performance, boxing, sorting, half-edge, subdivision, memory]
---

# Integer boxing kills mesh topology builds

## Context

`QuadMeshTopologyHelper.build` used `Integer[]` arrays with `Comparator`-based sorting to find half-edge twins and deduplicate edges. At subdivision level 6 (~6M half-edges), this created ~12M `Integer` objects (~200 MB) plus multiple `long[]` temp arrays (~300 MB), totaling ~500 MB of temporary allocations. This caused OOM at level 7 and 2+ second runtimes at level 6.

## Decision

Replaced sorting with a per-vertex outgoing half-edge CSR (Compressed Sparse Row) built from `faceIndices`. Twin lookup becomes O(valence) per half-edge (~4 for quads) instead of O(HE log HE) sorting. Edge deduplication derives directly from twin pairs in a single pass. Removed stored `v0[]` and `v1[]` arrays — callers compute vertex endpoints inline from `faceIndices`.

Total temporary memory dropped from ~500 MB to ~50 MB at level 6. Runtime dropped from ~2s to sub-300ms.

## Evidence

All 14 mesh tests pass. `ToolQuiltDslTest` with 6 subdivision levels completes in 0.018–0.278s (previously 2+ seconds). No OOM at level 6 with 1 GB heap.

## Reuse Trigger

Any time you build topology (twin/edge) for a uniform face mesh. If you see `Integer[]` or boxed-type sorting on mesh-sized arrays, replace with CSR adjacency lookup.

## Anti-pattern

Never use `Integer[]` with `Comparator` sorting on mesh-sized arrays. The boxing overhead is catastrophic: each `Integer` is 16 bytes on 64-bit JVM, so `Integer[N]` costs `16N` bytes beyond the `4N` bytes of the equivalent `int[]`. For `N > 1M`, this difference alone exceeds 10 MB, and the GC pressure from millions of short-lived objects degrades throughput further.
