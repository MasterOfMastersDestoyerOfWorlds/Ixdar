"""afterFileEdit hook: reject direct ticket edits that cause collisions.

Agent edits to ticket JSONs are blocked. Use the CLI instead:
  python ixdar-tickets/generate_board.py create ...
  python ixdar-tickets/generate_board.py update ...
  python ixdar-tickets/generate_board.py mark done ...
"""

import json
import sys
from pathlib import Path


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


def parse_ticket_path(file_path: str) -> tuple[Path, str, str] | None:
    """Extract (tickets_root, subdirectory, filename) from a ticket file path.

    Returns None if the path is not a ticket JSON under content/ or done/.
    """
    resolved = Path(file_path).resolve()
    parts = resolved.parts
    for i, part in enumerate(parts):
        if part.endswith("tickets") and i + 3 < len(parts):
            subdir = parts[i + 1]
            if subdir in ("content", "done") and parts[i + 3].endswith(".json"):
                if parts[i + 3] == "epics.json":
                    return None
                tickets_root = Path(*parts[: i + 1]) if i > 0 else Path(parts[0])
                epic = parts[i + 2]
                filename = parts[i + 3]
                return tickets_root, f"{subdir}/{epic}", filename
    return None


def check_collision(tickets_root: Path, subdir: str, filename: str) -> str | None:
    """If a content/ ticket collides with an archived done/ ticket, return an error message."""
    if not subdir.startswith("content/"):
        return None
    epic = subdir.split("/", 1)[1]
    done_path = Path(tickets_root) / "done" / epic / filename
    if done_path.exists():
        ticket_id = filename.removesuffix(".json")
        return (
            f"COLLISION: {ticket_id} already exists in done/{epic}/{filename}. "
            f"Use the CLI to get the next available ID: "
            f"python ixdar-tickets/generate_board.py next-id {epic}"
        )
    return None


def main() -> int:
    payload = read_payload()
    file_path = extract_file_path(payload)

    parsed = parse_ticket_path(file_path) if file_path else None
    if parsed is None:
        print(json.dumps({"decision": "allow"}))
        return 0

    tickets_root, subdir, filename = parsed

    collision = check_collision(tickets_root, subdir, filename)
    if collision:
        print(json.dumps({
            "decision": "reject",
            "reason": collision,
        }))
        return 0

    reject_msg = (
        f"Do not edit ticket files directly. Use the CLI instead:\n"
        f"  python ixdar-tickets/generate_board.py create ...   (new tickets)\n"
        f"  python ixdar-tickets/generate_board.py update ...   (modify existing)\n"
        f"  python ixdar-tickets/generate_board.py mark done ... (mark done)"
    )
    print(json.dumps({
        "decision": "reject",
        "reason": reject_msg,
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
