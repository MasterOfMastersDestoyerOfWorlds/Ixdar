---
title: Automation Debug Playbook
category: playbook
severity: medium
modules: [platform, automation]
tags: [automation, debugging, cli, health-check, troubleshooting]
---

# Automation Debug Playbook

Use this for `ixdar-app/src/main/java/ixdar/platform/automation/` or CLI regressions.

## Steps

1. Confirm single running app process.
2. Check `/health` and `/ui/state` first.
3. Validate click/type endpoints on menu screen.
4. Verify render-thread queue is draining each frame.
5. Validate replay status transitions (`running`, `paused`, `cancelled`, `completed`).

## Quick Command Set

- `uv run ixdar-cli health`
- `uv run ixdar-cli ui-state`
- `uv run ixdar-cli start-new-game`
