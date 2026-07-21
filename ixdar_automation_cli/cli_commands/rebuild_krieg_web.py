"""Build TeaVM output for Ixdar, then run Hugo for Krieg Eterna (sibling repo by default).

Exposed as the `ixdar-cli rebuild-krieg-web` subcommand. Override the web dir with
KRIEG_ETERNA_WEB=/path/to/KriegEterna/web. Child process output goes to stderr so the CLI's
JSON stdout stays valid.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from ..cli_registry import CliCommandResult, cli_command


def _ixdar_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _krieg_web_dir() -> Path:
    env = os.environ.get("KRIEG_ETERNA_WEB", "").strip()
    if env:
        return Path(env).resolve()
    return _ixdar_root().parent / "KriegEterna" / "web"


def run() -> dict:
    """Run the TeaVM package build then the Hugo build, returning a structured result.

    :return: ``{"ok": bool, "steps": [{"step", "returncode"}], ...}``; ``ok`` is false if either
        build fails or the Krieg Eterna web directory is missing.
    """
    ix = _ixdar_root()
    web = _krieg_web_dir()
    if not web.is_dir():
        return {
            "ok": False,
            "error": f"Krieg Eterna web dir not found: {web}. Set KRIEG_ETERNA_WEB to your KriegEterna/web path.",
        }

    steps = []
    mvn = subprocess.run(
        ["mvn", "-q", "package", "-pl", "ixdar-app", "-P", "web-teavm", "-DskipTests"],
        cwd=ix,
        check=False,
        stdout=sys.stderr,
        stderr=sys.stderr,
    )
    steps.append({"step": "mvn package -P web-teavm", "returncode": mvn.returncode})
    if mvn.returncode != 0:
        return {"ok": False, "steps": steps}

    hugo = subprocess.run(["hugo", "-D"], cwd=web, check=False, stdout=sys.stderr, stderr=sys.stderr)
    steps.append({"step": "hugo -D", "returncode": hugo.returncode})
    return {"ok": hugo.returncode == 0, "steps": steps}


@cli_command(name="rebuild-krieg-web")
def rebuild_krieg_web() -> CliCommandResult:
    """Build the TeaVM web output then run Hugo for Krieg Eterna (KRIEG_ETERNA_WEB overrides path)."""
    payload = run()
    return CliCommandResult(payload=payload, exit_code=0 if payload.get("ok") else 1)
