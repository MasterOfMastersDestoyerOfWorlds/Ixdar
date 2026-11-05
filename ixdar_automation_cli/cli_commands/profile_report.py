"""Read an async-profiler capture that already exists on disk.

``run-scene`` and ``launch`` print this report automatically at the end of a run, since every scene
is profiled. This command is for the other cases: a kept-alive scene's capture after ``shutdown``,
re-reading a capture without paying to re-run the scene, or asking a
follow-up question of one — which frames a named method's time actually went to, whether a suspected
hot path appears at all.

The parsing lives in :mod:`ixdar_automation_cli.async_profile`, which reconstructs async-profiler's
prefix-compressed constant pool and replays its frame stream to attribute *self* time per method.
"""

from typing import Annotated

from ..async_profile import format_hot_methods
from ..cli_registry import CliCommandResult, CliOption, cli_command
from .link_profiler import profile_capture


@cli_command(name="profile-report")
def profile_report(
    path: Annotated[str, CliOption(positional=True)] = "",
    top: int = 25,
    frame: list[str] | None = None,
) -> CliCommandResult:
    """Report self-time hot methods from an async-profiler HTML capture.

    :param path: Path to the async-profiler HTML capture (default: the newest under tmp/profiles).
    :param top: How many self-time rows to report.
    :param frame: Repeatable substring; matching frames get an inclusive-vs-self table.
    """
    path = path or profile_capture()
    if not path:
        return CliCommandResult(payload="no capture under tmp/profiles; name one with --path", exit_code=1)
    report = format_hot_methods(path, top=top, contains=frame or None)
    return CliCommandResult(payload={"path": path, "report": report.splitlines()})
