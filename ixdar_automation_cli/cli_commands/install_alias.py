"""Install a global ``ixdar-cli`` wrapper into ``~/.local/bin``.

The wrapper execs ``uv run --project <repo> ixdar-cli`` so it works from any directory and any shell,
and survives venv rebuilds. A fresh clone runs ``uv sync && uv run ixdar-cli install-alias`` once.
On Windows the wrapper is an ``ixdar-cli.cmd`` batch file, which ``cmd`` and PowerShell both run.
"""

import os
import stat
from pathlib import Path

from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command

DEFAULT_LOCAL_BIN = Path.home() / ".local" / "bin"

POSIX_WRAPPER = ("ixdar-cli", """#!/bin/sh
exec uv run --project "{repo}" ixdar-cli "$@"
""")
WINDOWS_WRAPPER = ("ixdar-cli.cmd", """@echo off
uv run --project "{repo}" ixdar-cli %*
""")


def install_alias(local_bin: Path = DEFAULT_LOCAL_BIN, repo_root: Path | None = None,
                  windows: bool = os.name == "nt") -> dict:
    """Write an executable ``ixdar-cli`` wrapper into ``local_bin`` idempotently.

    :param local_bin: directory to install the wrapper into (defaults to ~/.local/bin)
    :param repo_root: repo the wrapper should target with ``uv run --project``; ``None`` uses the
        checkout this call is made from
    :param windows: write the ``.cmd`` wrapper instead of the POSIX shell one
    :return: ``{"ok", "wrapper", "repo", "on_path", "hint"?}``
    """
    repo = repo_root or Path(checkout_root())
    name, template = WINDOWS_WRAPPER if windows else POSIX_WRAPPER
    local_bin.mkdir(parents=True, exist_ok=True)
    wrapper = local_bin / name
    wrapper.write_text(template.format(repo=repo))
    wrapper.chmod(wrapper.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    path_dirs = os.environ.get("PATH", "").split(os.pathsep)
    on_path = str(local_bin) in path_dirs
    result = {
        "ok": True,
        "wrapper": str(wrapper),
        "repo": str(repo),
        "on_path": on_path,
    }
    if not on_path:
        result["hint"] = (f"Add {local_bin} to PATH in your user environment variables" if windows
                          else f'Add to your shell rc: export PATH="{local_bin}:$PATH"')
    return result


@cli_command(name="install-alias")
def install_alias_command() -> CliCommandResult:
    """Install a global ixdar-cli wrapper into ~/.local/bin (ixdar-cli.cmd on Windows)."""
    return CliCommandResult(payload=install_alias())
