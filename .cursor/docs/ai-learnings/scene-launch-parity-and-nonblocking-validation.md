---
title: Scene Launch Parity and Non-Blocking Validation
category: automation
severity: medium
modules: [scenes, automation, build]
tags: [maven, launch-config, validation, screenshot, automation-cli, blocking]
---

# Scene Launch Parity and Non-Blocking Validation

## Context
Each `@SceneAnnotation` standalone scene has a `.vscode/launch.json` entry for IDE debugging. Maven profiles in `ixdar-app/pom.xml` provide the same launch capability from the terminal. These must stay in sync. Additionally, running `mvn -P<scene-profile>` blocks until the window closes, preventing the agent from capturing screenshots or running automation checks mid-session.

## Decision
1. **Launch parity**: Every `IxdarWindow` scene entry in `.vscode/launch.json` must have a matching `<profile>` in `ixdar-app/pom.xml` using the `exec:exec` pattern with the scene ID as the final argument. When adding a new scene, add both in the same change set.

2. **Non-blocking validation**: Launch the Maven profile with `block_until_ms: 0` to background it. Then use the automation CLI for health-check, screenshot, and shutdown:
   ```bash
   # 1. Background launch
   mvn -pl ixdar-app -P<scene-profile>   # with block_until_ms: 0

   # 2. Wait for startup, then validate
   uv run ixdar-cli health
   uv run ixdar-cli screenshot --out /tmp/scene.png

   # 3. Clean shutdown
   uv run ixdar-cli shutdown
   ```

3. **CLI retry resilience**: `AutomationClient.request_json()` retries 3 times with exponential backoff (1s, 2s, 4s) on `URLError`, `ConnectionError`, and `TimeoutError`. This handles transient failures during app startup without needing manual sleep-and-retry loops in agent scripts.

## Evidence
Agent attempted `mvn -Parrow-line-scene` which blocked for 68 seconds until the window was manually closed. No mid-run validation was possible. The automation server was confirmed running on port 47832 during the session.

## Reuse Trigger
- Adding a new `@SceneAnnotation` scene (add both launch.json entry and Maven profile).
- Any agent workflow that needs to visually verify a running scene (use background launch + CLI screenshot).

## Anti-pattern
- Running the Maven scene profile in foreground and assuming the agent can inspect the window — it blocks until exit.
- Adding a launch.json entry without a matching Maven profile (or vice versa), causing drift between IDE and terminal launch paths.
