---
name: create-ixdar-ticket
description: Creates and updates JSON backlog tickets in ixdar-tickets via generate_board.py only (never hand-edit JSON). Use when the user asks for a new ticket, backlog item, DSL/IX epic task, or follow-on work tracked in ixdar-tickets.
---

# Create Ixdar ticket (CLI only)

## Rules

1. **Never** use Write/StrReplace on `ixdar-tickets/content/**/*.json` or `ixdar-tickets/done/**/*.json`.
2. **Always** mutate tickets through `uv run python ixdar-tickets/generate_board.py` from the **Ixdar repository root** (`c:\Code\Ixdar`, the directory containing `ixdar-tickets/`).
3. **Always use `uv run`** to invoke Python. Bare `python` may resolve to the wrong interpreter or a stale venv. `uv run` ensures the lockfile-pinned environment.
4. If the CLI cannot express a change, extend `ixdar-tickets/generate_board.py` first, then use the CLI (see project rule `ticket-lifecycle.mdc`).

## Create a ticket

Run from repo root (`c:\Code\Ixdar`). All arguments on one line (PowerShell doesn't need line continuations for single-line commands):

```powershell
uv run python ixdar-tickets/generate_board.py create --epic DSL --repo Ixdar --title "Short title" --description "Full description of scope and constraints." --subsystem geometry --priority 2 --definition-of-done "1. ... 2. ..." --testing-plan "1. Unit ... 2. Manual ..." --todo "First concrete task" --todo "Second task"
```

Repeat `--todo` for each task line. Fields like `blocked-by` are not on `create` yet—extend `generate_board.py` first if you need them.

- **`--epic`**: Prefix matching `ixdar-tickets/content/<EPIC>/` (e.g. `DSL`, `IX`). IDs are auto-assigned.
- **`--subsystem`**: Use IDs from `ixdar-tickets/subsystems.json` (e.g. `geometry`, `automation`).
- **`--priority`**: Lower = higher priority; omit to auto-increment within epic.
- **`--blocked-by` / `--blocks`**: Optional ticket ID lists.

Preview next ID (optional): `uv run python ixdar-tickets/generate_board.py next-id DSL`

## Update / complete

```powershell
uv run python ixdar-tickets/generate_board.py update DSL-5 --status IN_PROGRESS --add-changes "Summary" --add-files "path/to/File.java" --done-todos 0 1
uv run python ixdar-tickets/generate_board.py update DSL-5 --undo-todos 2
uv run python ixdar-tickets/generate_board.py update DSL-5 --set-todos "Rewritten task A" "Rewritten task B"
uv run python ixdar-tickets/generate_board.py mark done DSL-5
uv run python ixdar-tickets/generate_board.py board
```

## Agent checklist

- [ ] `Set-Location "c:\Code\Ixdar"` (repo root where `ixdar-tickets/` lives).
- [ ] Use `uv run python ixdar-tickets/generate_board.py ...` — never bare `python`.
- [ ] Pick epic and subsystem from existing conventions.
- [ ] Write `definition-of-done` and `testing-plan` as numbered criteria.
- [ ] Link dependencies with `--blocked-by` / `--blocks` when relevant.
- [ ] Run `create`; confirm printed path and new ticket id in output.
