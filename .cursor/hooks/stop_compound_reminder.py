import json
from datetime import datetime, timezone
from pathlib import Path


OUT_PATH = Path(".cursor/logs/compound-reminders.log")


def main() -> int:
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("a", encoding="utf-8") as f:
        f.write(
            f"{datetime.now(timezone.utc).isoformat()} stop hook: "
            "capture learnings in docs/ai-learnings/ if work was non-trivial.\n"
        )
    print(
        json.dumps(
            {
                "decision": "allow",
                "message": "Remember to capture reusable learnings in docs/ai-learnings/."
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
