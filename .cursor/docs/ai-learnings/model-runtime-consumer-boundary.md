---
title: Model Runtime Consumer Boundary
category: architecture
severity: medium
modules: [graphics, scenes]
tags: [architecture, rendering, models, separation-of-concerns, pipeline]
---

# Model Runtime Consumer Boundary

## Context
- We added ASSIMP model loading and triangle rendering for `model-load-canvas`.
- Initial implementation put import + GPU upload + render logic directly in `ModelLoadScene`.
- We also needed repeatable runtime verification using `tools/ixdar-automation-cli`.
- Later, `MeshNodeViewerScene` started with hardcoded cube upload/render code inline before `HalfEdgeMeshRuntime` moved that pipeline behind the same kind of runtime boundary.

## Decision
- Enforce a consumer boundary: scenes should consume a simple model runtime interface, not implement model pipeline internals.
- Extract pipeline into reusable runtime types:
  - `ModelRuntime`
  - `ModelHandle`
  - `AssimpModelRuntime`
- Apply the same pattern to procedural mesh viewing: keep `MeshNodeViewerScene` focused on owning a `HalfEdgeMesh`, camera controls, and scene stats while `HalfEdgeMeshRuntime` owns compilation/upload/reupload/render/dispose details.
- Keep scene responsibilities minimal: load model, frame camera, call render.
- Add/keep CLI-first iterative checks (`probe`) for fast runtime validation.

## Evidence
- `ModelLoadScene` reduced to thin orchestration over `ModelRuntime`.
- Mesh import/upload/draw moved to `ixdar.graphics.render.model.*`.
- Indexed drawing support added across GL abstraction (`drawElements`, `ELEMENT_ARRAY_BUFFER`, `UNSIGNED_INT`).
- New `probe` command in `tools/ixdar-automation-cli/ixdar_cli.py` used to verify health/ui/screenshot in one call.
- Runtime verification succeeded on dedicated automation port with ASSIMP mesh stats logged.
- `HalfEdgeMeshRuntime` now consumes compiled mesh data from `HalfEdgeMesh`, `MeshNodeViewerScene` no longer owns VAO/VBO/EBO setup, and automation validation passed through `health`, `mesh-state`, and `screenshot`.

## Reuse Trigger
- Use this pattern whenever a scene starts owning parsing, buffering, or backend-specific render details.
- Use this pattern whenever a scene starts owning topology compilation or GPU upload logic for procedural geometry.
- Use CLI `probe` whenever visual/runtime validation is needed during iterative graphics work.

## Anti-pattern
- Treating a scene as both product feature and rendering framework (importer + uploader + renderer + camera policy all in one file).
- Keeping hardcoded mesh buffer creation inside a scene after a reusable runtime boundary exists.
- Testing graphics changes only manually without automation snapshots/health checks.
