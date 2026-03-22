import json
import sys
from datetime import datetime, timezone
from pathlib import Path


OUT_PATH = Path(".cursor/logs/compound-reminders.log")


def main() -> int:
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    reminder_parts = [
        "If this session involved non-trivial work:",
        "1. Search .cursor/docs/ai-learnings/ (grep tags/title) for related learnings before writing new ones.",
        "2. Capture or update a learning with YAML frontmatter (title, category, severity, modules, tags).",
        "3. Triage: promote general patterns to ixdar-coding-standards.mdc or ixdar.mdc; stage one-off gotchas.",
        "4. For ticketed work, update ticket status and prefix completed todos with 'DONE : '.",
    ]
    reminder = " ".join(reminder_parts)

    with OUT_PATH.open("a", encoding="utf-8") as f:
        f.write(f"{datetime.now(timezone.utc).isoformat()} stop hook: {reminder}\n")

    print(
        json.dumps(
            {
                "decision": "allow",
                "message": reminder,
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
