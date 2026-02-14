## Context
Audio + automation validation surfaced two recurring failures: stale app instances holding automation port `47832`, and runtime audio load failures when asset-root env vars were unavailable in launch context.

## Decision
- Validate with exactly one running Ixdar app process per workspace.
- Make service startup/shutdown symmetrical (automation server start must have explicit stop on app shutdown).
- Package runtime audio into jar classpath resources via Maven, and avoid runtime dependence on env vars.

## Evidence
- Port bind failures observed: `Address already in use: bind` when previous instance did not fully exit.
- Window close did not free port until automation server + executor were explicitly stopped.
- Audio load failure observed when runtime could not resolve `IXDAR_ASSET_REPO_ROOT`; fixed by classpath-first loading of Maven-packaged audio resources and verified with CLI telemetry.

## Reuse trigger
Apply this pattern whenever adding HTTP servers, replay engines, worker pools, or new runtime asset loaders.
Before trusting CLI validation results, verify automation is pointed at the intended process by checking `health`/`ui-state` and enforcing one running Ixdar instance.

## Anti-pattern
- Starting background services without a stop path.
- Running multiple app instances during automation checks.
- Assuming environment variables present at Maven build time are also present in runtime launchers.
