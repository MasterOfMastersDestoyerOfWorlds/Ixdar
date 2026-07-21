"""Install a global ``ixdar-cli`` wrapper into ``~/.local/bin``.

The wrapper execs ``uv run --project <repo> ixdar-cli`` so it works from any directory and any shell,
and survives venv rebuilds. Mirrors ``tools/install-cli.sh`` for devs who already have the CLI.
"""

import os
import stat
from pathlib import Path

from ..cli_registry import CliCommandResult, cli_command

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LOCAL_BIN = Path.home() / ".local" / "bin"

WRAPPER_TEMPLATE = """#!/usr/bin/env bash
exec uv run --project "{repo}" ixdar-cli "$@"
"""


def install_alias(local_bin: Path = DEFAULT_LOCAL_BIN, repo_root: Path = REPO_ROOT) -> dict:
    """Write an executable ``ixdar-cli`` wrapper into ``local_bin`` idempotently.

    :param local_bin: directory to install the wrapper into (defaults to ~/.local/bin)
    :param repo_root: repo the wrapper should target with ``uv run --project``
    :return: ``{"ok", "wrapper", "repo", "on_path", "hint"?}``
    """
    local_bin.mkdir(parents=True, exist_ok=True)
    wrapper = local_bin / "ixdar-cli"
    wrapper.write_text(WRAPPER_TEMPLATE.format(repo=repo_root))
    wrapper.chmod(wrapper.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    path_dirs = os.environ.get("PATH", "").split(os.pathsep)
    on_path = str(local_bin) in path_dirs
    result = {
        "ok": True,
        "wrapper": str(wrapper),
        "repo": str(repo_root),
        "on_path": on_path,
    }
    if not on_path:
        result["hint"] = f'Add to your shell rc: export PATH="{local_bin}:$PATH"'
    return result


@cli_command(name="install-alias")
def install_alias_command() -> CliCommandResult:
    """Install a global ixdar-cli wrapper into ~/.local/bin."""
    return CliCommandResult(payload=install_alias())