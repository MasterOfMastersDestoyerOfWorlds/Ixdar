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

## Evidence

`python -m unittest test_cli.py`

`uv run ixdar-cli --help`

`uv run ixdar-cli assert-tooltip --help`

## Reuse Trigger

Apply this pattern when a Python CLI in the repo starts duplicating command names, help text, defaults, and dispatch logic across one monolithic entrypoint.

## Anti-pattern

Do not force grouped or non-runtime commands into the first registry pass if they need nested subparsers or different side-effect behavior. A mixed rollout with compatibility shims is safer than overfitting the registry and regressing stable operator flows.
