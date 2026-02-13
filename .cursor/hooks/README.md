# Cursor Hooks

Configured in `.cursor/hooks.json`.

## Hooks in this repo

- `pre_tool_guard.py` blocks dangerous shell patterns.
- `post_write_audit.py` logs write operations for traceability.
- `stop_compound_reminder.py` writes a compounding reminder at task end.

## Notes

- Hooks are fail-open by default unless explicitly denied.
- Blocking uses exit code `2` with a JSON deny payload.
