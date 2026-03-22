---
title: Half-Edge Mesh Uses Paired Boundary HalfEdges
category: architecture
severity: medium
modules: [geometry, mesh]
tags: [mesh, half-edge, topology, boundary, adjacency]
---

# Half-Edge Mesh Uses Paired Boundary HalfEdges

## Context

`MESH-1` needed a core mesh representation that supports O(1) adjacency lookups, face deletion, boundary detection, large procedural builds, and a later compiled-surface handoff to rendering without special-case topology branches.

## Decision

Represent each undirected edge as a paired half-edge pair even in the packed, index-based mesh storage. The boundary side remains as a real twin half-edge with `face == -1`, which keeps `twin` lookups stable and makes shared-edge promotion/removal logic simpler than sparse one-sided edges. Keep the public mesh API semantic and ID-based so baking/render code does not depend on storage internals.

## Evidence

`mvn clean -Dtest=HalfEdgeMeshTest test` passed in `ixdar-app/`, covering closed cube topology, open-plane boundary detection, Euler characteristic on the icosahedron seed mesh, face removal cleanup, normal computation, non-manifold rejection, compiled-surface export, and a 100k-face-class procedural grid build.

## Reuse Trigger

Apply this when extending `HalfEdgeMesh`, adding mesh editing operations, or writing mesh runtime/export code that needs reliable `halfEdgeTwin`, `isBoundaryEdge`, or adjacency traversal after deletions.

## Anti-pattern

Creating only one half-edge for boundary edges and treating the missing twin as implicit, or exposing packed storage arrays directly to consumers. That pushes null/sentinel checks into every traversal path, makes `removeFace` / shared-edge transitions more error-prone, and couples bake/runtime code to topology internals.
