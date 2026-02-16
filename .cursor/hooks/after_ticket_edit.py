"""afterFileEdit hook: regenerate BOARD.md when a ticket JSON is modified."""

import json
import subprocess
import sys
from pathlib import PurePosixPath


def read_payload() -> dict:
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except Exception:
        return {}


def extract_file_path(payload: dict) -> str:
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


def is_ticket_file(file_path: str) -> bool:
    normalized = file_path.replace("\\", "/")
    parts = PurePosixPath(normalized).parts
    # Match paths ending in tickets/content/<CATEGORY>/<ID>.json
    for i, part in enumerate(parts):
        if part == "tickets" and i + 3 < len(parts):
            if parts[i + 1] == "content" and parts[i + 3].endswith(".json"):
                # Skip epics.json
                if parts[i + 3] == "epics.json":
                    return False
                return True
    return False


def main() -> int:
    payload = read_payload()
    file_path = extract_file_path(payload)
    if file_path and is_ticket_file(file_path):
        subprocess.Popen(
            [sys.executable, "tickets/generate_board.py"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    print(json.dumps({"decision": "allow"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
