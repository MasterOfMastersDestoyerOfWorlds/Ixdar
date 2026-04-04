#!/usr/bin/env python3
"""
Build TeaVM output for Ixdar, then run Hugo for Krieg Eterna (sibling repo by default).

Usage (from Ixdar repo root):
  uv run rebuild-krieg-web

Override paths:
  KRIEG_ETERNA_WEB=/path/to/KriegEterna/web
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def _ixdar_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _krieg_web_dir() -> Path:
    env = os.environ.get("KRIEG_ETERNA_WEB", "").strip()
    if env:
        return Path(env).resolve()
    return _ixdar_root().parent / "KriegEterna" / "web"


def main() -> None:
    ix = _ixdar_root()
    web = _krieg_web_dir()
    if not web.is_dir():
        sys.stderr.write(
            f"Krieg Eterna web dir not found: {web}\n"
            "Set KRIEG_ETERNA_WEB to your KriegEterna/web path.\n"
        )
        sys.exit(1)

    mvn = subprocess.run(
        [
            "mvn",
            "-q",
            "package",
            "-pl",
            "ixdar-app",
            "-P",
            "web-teavm",
            "-DskipTests",
        ],
        cwd=ix,
        check=False,
    )
    if mvn.returncode != 0:
        sys.exit(mvn.returncode)

    hugo = subprocess.run(["hugo", "-D"], cwd=web, check=False)
    if hugo.returncode != 0:
        sys.exit(hugo.returncode)


if __name__ == "__main__":
    main()
