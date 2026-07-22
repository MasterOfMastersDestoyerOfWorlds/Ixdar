"""Read an async-profiler capture that already exists on disk.

``run-scene --profile`` prints this report automatically at the end of a profiled run. This command
is for the other case: re-reading a capture without paying to re-run the scene, or asking a
follow-up question of one — which frames a named method's time actually went to, whether a suspected
hot path appears at all.

The parsing lives in :mod:`ixdar_automation_cli.async_profile`, which reconstructs async-profiler's
prefix-compressed constant pool and replays its frame stream to attribute *self* time per method.
"""

from ..async_profile import format_hot_methods
from ..cli_registry import CliCommandResult, cli_command


DEFAULT_PROFILE = "profile.html"


@cli_command(name="profile-report")
def profile_report(
    path: str = DEFAULT_PROFILE,
    top: int = 25,
    frame: list[str] | None = None,
) -> CliCommandResult:
    """Report self-time hot methods from an async-profiler HTML capture.

    :param path: Path to the async-profiler HTML capture.
    :param top: How many self-time rows to report.
    :param frame: Repeatable substring; matching frames get an inclusive-vs-self table.
    """
    report = format_hot_methods(path, top=top, contains=frame or None)
    return CliCommandResult(payload={"path": path, "report": report.splitlines()})
