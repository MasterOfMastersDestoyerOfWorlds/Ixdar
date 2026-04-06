# Cursor Hooks

Configured in `.cursor/hooks.json`.

## Hooks in this repo

- `pre_tool_guard.py` blocks dangerous shell patterns.
- `post_write_audit.py` logs write operations for traceability.
- `shader_validation_gate.py` enforces screenshot validation after editing shader/scene files.
- `stop_compound_reminder.py` writes a compounding reminder at task end.

## Shader/Scene Validation Gate

The `shader_validation_gate.py` hook implements a three-touchpoint mechanism to enforce
screenshot validation whenever rendering code is edited:

### Touchpoint 1: afterFileEdit
When editing shader files (`.fs`, `.vs`, `.glsl`) or Java files under a `scenes/` package,
the hook:
- Logs the edit to `.cursor/logs/validation-pending.log`
- Returns a reminder message instructing the agent to validate with a screenshot before stopping

### Touchpoint 2: postToolUse (Shell)
When a screenshot command is detected via the automation CLI (e.g., `ixdar-cli screenshot`),
the hook clears the validation-pending log, indicating that visual validation has been performed.

### Touchpoint 3: stop
When the session attempts to stop, the hook checks if there are any uncleared entries
in the validation-pending log. If so, it denies the stop with a clear message directing
the agent to validate first with a screenshot.

### Validation Log
The validation-pending log (`.cursor/logs/validation-pending.log`) tracks which shader/scene
files were edited. Each line contains a timestamp and file path. The log is cleared when
a screenshot command is executed.

### Example Usage
```bash
# Edit a shader file - hook logs the edit and returns reminder
# ... then later ...

# Take a screenshot to validate - hook clears the pending log
ixdar-cli screenshot

# Stop - hook allows because validation was performed
```

## Notes

- Hooks are fail-open by default unless explicitly denied.
- Blocking uses exit code `2` with a JSON deny payload.
