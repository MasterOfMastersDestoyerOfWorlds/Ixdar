---
title: Standalone Scene Launch Configs
category: tooling
severity: low
modules: [scenes, vscode-config]
tags: [vscode, launch-config, scenes, debugging, env-vars]
---

# Standalone Scene Launch Configs

## Context
- Standalone scene classes are discoverable by scene ID, but debugging them in VS Code is slower unless each scene has an explicit launch profile.
- The new model-loading standalone scene requires `IXDAR_ASSET_REPO_ROOT` to be present at launch time.

## Decision
- Add a dedicated `.vscode/launch.json` entry for each standalone scene that needs direct debugging.
- For external-asset scenes, include required env vars directly in the launch profile (for local default paths).

## Evidence
- Added `Model Load` launch config in `.vscode/launch.json` with args `model-load-canvas`.
- Added `env.IXDAR_ASSET_REPO_ROOT = C:\\Code\\IxdarAssets` in the same profile so `Hand.obj` resolves without manual shell setup.

## Reuse Trigger
- Use this pattern when adding any new `@SceneAnnotation` standalone scene, especially scenes with non-resource filesystem dependencies.

## Anti-pattern
- Relying on `Launch Current File` for scene testing, which skips required scene args/env setup and causes avoidable runtime load failures.
