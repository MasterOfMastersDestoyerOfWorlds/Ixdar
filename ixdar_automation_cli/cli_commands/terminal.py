"""Run a line in the scene's terminal and read back what it printed."""

from typing import Annotated

from ..automation_client import DEFAULT_SETTLE_FRAMES, AutomationClient
from ..cli_registry import CliCommandResult, CliOption, cli_command


@cli_command(name="terminal")
def terminal(
    client: AutomationClient,
    line: Annotated[str, CliOption(positional=True)],
    settle: int = DEFAULT_SETTLE_FRAMES,
) -> CliCommandResult:
    """Type a line into the scene terminal, press enter, and return the terminal's response.

    The terminal is focused first and left focused, so a screenshot taken straight afterwards shows
    the command and its output.

    :param line: Command line to run, arguments included, e.g. "rings list".
    :param settle: Frames to wait for after the command runs, so no sleep is needed.
    """
    payload = client.terminal(line, settle=settle)
    return CliCommandResult(payload=payload, exit_code=0 if payload.get("ok") else 1)
