---
title: Premature MeshNode Registration Breaks Build
category: build
severity: medium
modules: [annotations, geometry, mesh]
tags: [meshnode, annotation-processor, registry, constructor, build]
---

# Premature MeshNode Registration Breaks Build

## Context

While implementing `MESH-5`, the reactor build failed in generated source because `Icosphere` was annotated with `@MeshNodeAnnotation` even though it is not a real mesh node and requires a constructor argument.

## Decision

Only annotate classes with `@MeshNodeAnnotation` when they are actual mesh node implementations and can be instantiated by the current generated registry. Until the `MeshNode` redesign lands, keep geometry/domain helpers like `Icosphere` unannotated.

## Evidence

`mvn -DskipTests compile` failed with an invalid constructor reference in `MeshNodeRegistry_MeshNodes` for `Icosphere::new`. Removing the premature annotation restored a successful reactor build.

## Reuse Trigger

Apply this check whenever adding a new mesh annotation, changing the mesh registry processor, or wiring generated suppliers for new node types.

## Anti-pattern

Annotating geometry helpers, scene test data, or constructor-parameterized classes as mesh nodes before they implement the real node contract and expose a registry-compatible constructor.
