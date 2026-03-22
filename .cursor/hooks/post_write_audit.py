import json
from datetime import datetime, timezone
from pathlib import Path
import sys


LOG_PATH = Path(".cursor/logs/hook-events.log")


def read_payload() -> dict:
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except Exception:
        return {}


def extract_target(payload: dict) -> str:
    for key in ("target_file", "file", "path"):
        value = payload.get(key)
        if isinstance(value, str) and value:
            return value
    tool_input = payload.get("toolInput")
    if isinstance(tool_input, dict):
        for key in ("target_file", "file", "path"):
            value = tool_input.get(key)
            if isinstance(value, str) and value:
                return value
    return "unknown"


def main() -> int:
    payload = read_payload()
    target = extract_target(payload)
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(f"{datetime.now(timezone.utc).isoformat()} postToolUse Write {target}\n")
    print(json.dumps({"decision": "allow"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
