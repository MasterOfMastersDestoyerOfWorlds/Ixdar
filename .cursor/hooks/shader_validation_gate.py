"""Shader/Scene Validation Gate: Enforces screenshot validation after editing rendering code.

This hook implements a three-touchpoint mechanism:
1. afterFileEdit: Detects shader (.fs, .vs, .glsl) and scene Java file edits, logs them
   to validation-pending.log, and returns a reminder message.
2. postToolUse Shell: Detects screenshot CLI invocations and clears the validation-pending
   log when a screenshot command is detected.
3. stop: Denies stop if validation-pending.log has uncleared entries, directing the agent
   to validate first with a screenshot.

The validation-pending.log file tracks which files were edited and when, ensuring that
any editing of rendering code requires visual validation before session completion.
"""

import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


# Shader file extensions that require validation
SHADER_EXTENSIONS = {".fs", ".vs", ".glsl"}

# Log file for tracking pending validations
VALIDATION_PENDING_LOG = Path(".cursor/logs/validation-pending.log")


def read_payload() -> dict:
    """Read and parse the JSON payload from stdin."""
    raw = sys.stdin.read().strip()
    print(f"DEBUG: raw={raw!r}", file=sys.stderr)
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def extract_file_path(payload: dict) -> str:
    """Extract file path from various payload structures."""
    for key in ("file_path", "target_file", "file", "path"):
        value = payload.get(key)
        if isinstance(value, str) and value:
            return value
    tool_input = payload.get("toolInput")
    if isinstance(tool_input, dict):
        for key in ("file_path", "target_file", "file", "path"):
            value = tool_input.get(key)
            if isinstance(value, str) and value:
                return value
    return ""


def extract_command(payload: dict) -> str:
    """Extract shell command from postToolUse Shell payload."""
    candidates = [
        payload.get("command"),
        payload.get("toolInput", {}).get("command") if isinstance(payload.get("toolInput"), dict) else None,
        payload.get("input", {}).get("command") if isinstance(payload.get("input"), dict) else None,
    ]
    for item in candidates:
        if isinstance(item, str) and item.strip():
            return item
    return ""


def is_shader_file(file_path: str) -> bool:
    """Check if the file is a shader file based on extension."""
    return Path(file_path).suffix.lower() in SHADER_EXTENSIONS


def is_scene_java_file(file_path: str) -> bool:
    """Check if the file is a Java file under a scenes/ package."""
    path = Path(file_path)
    if path.suffix.lower() != ".java":
        return False
    # Check if the path contains a 'scenes' directory component
    # This handles paths like /project/scenes/com/example/Scene.java
    return "scenes" in path.parts


def should_validate_edit(file_path: str) -> bool:
    """Determine if an edit requires validation (shader or scene file)."""
    return is_shader_file(file_path) or is_scene_java_file(file_path)


def log_validation_pending(file_path: str) -> None:
    """Log a file edit to the validation-pending log."""
    VALIDATION_PENDING_LOG.parent.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).isoformat()
    with VALIDATION_PENDING_LOG.open("a", encoding="utf-8") as f:
        f.write(f"{timestamp} {file_path}\n")


def get_pending_files() -> list[str]:
    """Read and return the list of files pending validation."""
    if not VALIDATION_PENDING_LOG.exists():
        return []
    try:
        with VALIDATION_PENDING_LOG.open("r", encoding="utf-8") as f:
            lines = f.readlines()
        # Extract file paths from log lines (format: timestamp file_path)
        pending = []
        for line in lines:
            parts = line.strip().split(" ", 1)
            if len(parts) >= 2:
                pending.append(parts[1])
        return pending
    except Exception:
        return []


def clear_validation_pending() -> None:
    """Clear the validation-pending log."""
    if VALIDATION_PENDING_LOG.exists():
        VALIDATION_PENDING_LOG.unlink()


def is_screenshot_command(command: str) -> bool:
    """Check if the shell command is a screenshot invocation."""
    # Match patterns like:
    # - python .cursor/hooks/shader_validation_gate.py ... screenshot
    # - ixdar-cli screenshot
    # - python -m ixdar_automation_cli.ixdar_cli screenshot
    # - ixdar screenshot
    # - python ixdar_cli.py screenshot
    screenshot_patterns = [
        r"\bscreenshot\b",  # Just the word "screenshot" in the command
    ]
    for pattern in screenshot_patterns:
        if re.search(pattern, command, re.IGNORECASE):
            return True
    return False


def after_file_edit_hook() -> int:
    """Handle afterFileEdit hook: log shader/scene edits and return reminder."""
    payload = read_payload()
    
    # Debug output
    import sys
    print(f"DEBUG: full_payload={payload}", file=sys.stderr)
    
    file_path = extract_file_path(payload)
    print(f"DEBUG: file_path={file_path}", file=sys.stderr)
    print(f"DEBUG: should_validate={should_validate_edit(file_path) if file_path else False}", file=sys.stderr)

    if not file_path or not should_validate_edit(file_path):
        print(json.dumps({"decision": "allow"}))
        return 0

    # Log the edit to validation-pending log
    log_validation_pending(file_path)

    # Return a reminder message instructing the agent to validate
    reminder = (
        f"Shader/scene file edited: {file_path}. "
        f"Before stopping this session, you MUST validate with a screenshot "
        f"by running: ixdar-cli screenshot (or python -m ixdar_automation_cli.ixdar_cli screenshot)"
    )

    print(json.dumps({
        "decision": "allow",
        "message": reminder,
    }))
    return 0


def post_tool_use_hook() -> int:
    """Handle postToolUse Shell hook: clear pending log when screenshot is taken."""
    payload = read_payload()
    command = extract_command(payload)

    if not command or not is_screenshot_command(command):
        print(json.dumps({"decision": "allow"}))
        return 0

    # Clear the validation-pending log when a screenshot command is detected
    clear_validation_pending()

    print(json.dumps({
        "decision": "allow",
        "message": "Screenshot taken - validation pending state cleared",
    }))
    return 0


def stop_hook() -> int:
    """Handle stop hook: deny if there are uncleared validation entries."""
    pending_files = get_pending_files()

    if not pending_files:
        print(json.dumps({"decision": "allow"}))
        return 0

    # Build a message listing all pending validations
    pending_list = "\n".join(f"  - {f}" for f in pending_files)
    denial_message = (
        f"Validation pending! The following shader/scene files were edited but not validated:\n"
        f"{pending_list}\n\n"
        f"Before stopping, you MUST take a screenshot to validate your changes:\n"
        f"  ixdar-cli screenshot\n"
        f"or\n"
        f"  python -m ixdar_automation_cli.ixdar_cli screenshot"
    )

    print(json.dumps({
        "decision": "deny",
        "reason": denial_message,
    }))
    return 2


def main() -> int:
    """Main entry point: dispatch based on hook type from stdin."""
    payload = read_payload()

    # Determine hook type from payload
    hook_type = payload.get("hook_type", "")

    if hook_type == "afterFileEdit":
        return after_file_edit_hook()
    elif hook_type == "postToolUse":
        return post_tool_use_hook()
    elif hook_type == "stop":
        return stop_hook()
    else:
        # Default: allow
        print(json.dumps({"decision": "allow"}))
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
