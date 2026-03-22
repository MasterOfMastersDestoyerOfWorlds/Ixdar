---
title: Registry-Driven Automation CLI Commands
category: tooling
severity: medium
modules: [automation]
tags: [automation-cli, cli, registry, argparse, docstrings, compatibility-shim]
---

# Registry-Driven Automation CLI Commands

## Context

The Ixdar automation CLI had grown into one large `argparse` setup and `if/elif` dispatcher in `tools/ixdar-automation-cli/ixdar_cli.py`. That made it easy for help text, parameter docs, and command behavior to drift apart as more automation commands were added.

## Decision

Move flat and scenario automation commands into explicit command modules decorated with a shared registry, and generate parsers from the registered function signatures plus docstring `:param` metadata. Keep nested or tooling-oriented commands such as `record`, `replay`, `validate route-ops`, and `new-scene` on small manual compatibility shims until the registry model needs to absorb those shapes.

Keep scene-specific extraction commands like `mesh-state` and `mesh-validate` as thin wrappers over the generic automation payload when the server already exposes stable machine-readable data. Reserve scenario commands like `mesh-probe` for multi-call workflows that intentionally bundle health, state, and screenshot steps.

## Evidence

`python -m unittest test_cli.py`

`uv run ixdar-cli --help`

`uv run ixdar-cli assert-tooltip --help`

`python -m unittest ixdar_automation_cli.test_cli`

`python -m ixdar_automation_cli.ixdar_cli mesh-validate`

`python -m ixdar_automation_cli.ixdar_cli mesh-probe --out tmp/mesh-probe.png`

## Reuse Trigger

Apply this pattern when a Python CLI in the repo starts duplicating command names, help text, defaults, and dispatch logic across one monolithic entrypoint.

Also apply it when adding validation helpers for a new scene or feature area: use flat registry commands for single-payload extraction/validation and scenario commands for bundled operator flows.

## Anti-pattern

Do not force grouped or non-runtime commands into the first registry pass if they need nested subparsers or different side-effect behavior. A mixed rollout with compatibility shims is safer than overfitting the registry and regressing stable operator flows.

Do not turn every convenience command into a scenario helper. If a command can be expressed as a stable transform of one automation payload with a clear exit-code policy, keep it as a thin flat registry command.
