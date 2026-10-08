"""Shut the running scene down and wait for the JVM to actually be gone.

The server route answers ``{"ok": true, "accepted": true}`` the instant it schedules the exit,
which is a lie by omission: the process is still holding its port, its GL context and its
profiler output for another second or two. Callers papered over that with ``pgrep`` loops that
had to be written to avoid matching the calling shell — so this command does the waiting.

With no ``--pid`` or ``--base-url`` the scene is the one headless scene of this checkout, so a
window the user opened with F5 is never shut down unless it is named.

Usage:
    uv run ixdar-cli shutdown
    uv run ixdar-cli shutdown --timeout 60
    uv run ixdar-cli --pid 12345 shutdown
"""

import time

from ..automation_client import AutomationClient, process_alive
from ..cli_registry import CliCommandResult, cli_command

POLL_SECONDS = 0.2

DEFAULT_SHUTDOWN_TIMEOUT = 30.0


def await_exit(pid: int, timeout: float) -> tuple[bool, float]:
    """Poll a process id until it is gone.

    :param pid: Process id to watch.
    :param timeout: Seconds to wait before giving up.
    :return: ``(exited, secondsWaited)``.
    """
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        if not process_alive(pid):
            return True, round(time.monotonic() - started, 1)
        time.sleep(POLL_SECONDS)
    return False, round(time.monotonic() - started, 1)


@cli_command(name="shutdown")
def shutdown(
    client: AutomationClient,
    timeout: float = DEFAULT_SHUTDOWN_TIMEOUT,
) -> CliCommandResult:
    """Ask the scene to exit and return only once its process is gone.

    The process id comes from the scene's own record under ``tmp/automation/``, so nothing has to
    match a JVM by command line — the idiom that used to kill the caller's own shell. With no
    target named, only this checkout's headless scene is ever stopped, never a windowed one.

    :param timeout: Seconds to wait for the process to disappear.
    """
    target = client.resolve()
    pid = target.get("pid")
    payload: dict = {"ok": True, "port": target.get("port"), "pid": pid}
    try:
        payload["accepted"] = bool(client.shutdown().get("accepted"))
    except Exception as unreachable:
        payload["accepted"] = False
        payload["error"] = f"no scene answered {client.base_url}: {unreachable}"
        payload["ok"] = not (pid and process_alive(pid))
        return CliCommandResult(payload=payload, exit_code=0 if payload["ok"] else 3)

    if not pid:
        payload["exited"] = None
        payload["note"] = (f"no scene record names port {target.get('port')}; "
                           "the scene was not started from this checkout")
        return CliCommandResult(payload=payload)

    exited, waited = await_exit(int(pid), timeout)
    payload["exited"] = exited
    payload["waitedSeconds"] = waited
    payload["ok"] = exited
    if not exited:
        payload["error"] = f"process {pid} still alive after {timeout}s"
    return CliCommandResult(payload=payload, exit_code=0 if exited else 1)
