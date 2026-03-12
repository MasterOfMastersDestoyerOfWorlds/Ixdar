---
title: MeshNode Port Value Types Belong in Annotations
category: architecture
severity: medium
modules: [annotations, geometry, mesh]
tags: [meshnode, ports, annotations, mesh, nodecontext, registry]
---

# MeshNode Port Value Types Belong in Annotations

## Context

`MESH-2` needed typed mesh node ports in the `annotations` module while real mesh values like `HalfEdgeMesh` live in `ixdar-app`. The node contract needed to stay cross-module, registry-friendly, and testable without making the annotations module depend on app geometry classes.

## Decision

Keep the node contract and port-related value abstractions in `annotations/src/main/java/ixdar/annotations/meshnode/`. Use small contract-level types there, such as `MeshValue`, `InputPort`, `OutputPort`, `PortType`, and `NodeContext`, then have app-side mesh classes implement the marker interfaces they need for typed ports.

## Evidence

`mvn -DskipTests install` in `annotations/`

`mvn clean -Dtest=CubeMeshNodeTest,HalfEdgeMeshTest,HalfEdgeMeshRuntimeTest,MeshNodeViewerSceneLifecycleTest test` in `ixdar-app/`

The first real mesh node, `CubeMeshNode`, compiled, evaluated, registered through `@MeshNodeAnnotation`, and passed type-validation tests without introducing an app dependency into the annotations module.

## Reuse Trigger

Apply this when adding new mesh node port types, context helpers, or contract-level node values that must be shared between the annotation processor module and app-side node implementations.

## Anti-pattern

Do not make the annotations module depend directly on app-side geometry/runtime classes just to express a port type. That couples the contract module to one implementation and makes later node/tooling work harder to evolve.
