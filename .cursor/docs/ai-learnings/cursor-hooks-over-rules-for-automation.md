---
title: Prefer Cursor Hooks Over Rules for Automated Side Effects
category: tooling
severity: high
modules: [cursor-config]
tags: [cursor, hooks, automation, rules, determinism]
promoted_to: ixdar.mdc
---

# Prefer Cursor Hooks Over Rules for Automated Side Effects

## Context
Needed to auto-regenerate `tickets/BOARD.md` whenever an agent modifies a ticket JSON. Initially planned to add a step to the `ticket-lifecycle.mdc` rule telling agents to run the generator script. User correctly pointed out that agents only sometimes follow rules.

## Decision
Used Cursor's `afterFileEdit` hook in `.cursor/hooks.json` instead. The hook script (`.cursor/hooks/after_ticket_edit.py`) checks if the edited file is a ticket JSON and fires `generate_board.py` via subprocess. This is deterministic -- it fires at the platform level regardless of what the agent does.

## Evidence
Existing hooks (`pre_tool_guard.py`, `post_write_audit.py`, `stop_compound_reminder.py`) already work reliably in this project. The `afterFileEdit` hook follows the same pattern: read JSON payload from stdin, extract file path, filter, act.

## Reuse Trigger
Any time you need a guaranteed side effect after an agent edits a specific file or file pattern. Rules are for guidance; hooks are for enforcement.

## Anti-pattern
Don't rely on cursor rules for actions that must happen every time. Rules are best-effort -- agents may skip steps, forget context, or be using a model that doesn't follow instructions well. Use hooks for anything that must be deterministic.
