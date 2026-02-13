## Context
- We added ASSIMP model loading and triangle rendering for `model-load-canvas`.
- Initial implementation put import + GPU upload + render logic directly in `ModelLoadScene`.
- We also needed repeatable runtime verification using `tools/ixdar-automation-cli`.

## Decision
- Enforce a consumer boundary: scenes should consume a simple model runtime interface, not implement model pipeline internals.
- Extract pipeline into reusable runtime types:
  - `ModelRuntime`
  - `ModelHandle`
  - `AssimpModelRuntime`
- Keep scene responsibilities minimal: load model, frame camera, call render.
- Add/keep CLI-first iterative checks (`probe`) for fast runtime validation.

## Evidence
- `ModelLoadScene` reduced to thin orchestration over `ModelRuntime`.
- Mesh import/upload/draw moved to `ixdar.graphics.render.model.*`.
- Indexed drawing support added across GL abstraction (`drawElements`, `ELEMENT_ARRAY_BUFFER`, `UNSIGNED_INT`).
- New `probe` command in `tools/ixdar-automation-cli/ixdar_cli.py` used to verify health/ui/screenshot in one call.
- Runtime verification succeeded on dedicated automation port with ASSIMP mesh stats logged.

## Reuse Trigger
- Use this pattern whenever a scene starts owning parsing, buffering, or backend-specific render details.
- Use CLI `probe` whenever visual/runtime validation is needed during iterative graphics work.

## Anti-pattern
- Treating a scene as both product feature and rendering framework (importer + uploader + renderer + camera policy all in one file).
- Testing graphics changes only manually without automation snapshots/health checks.
