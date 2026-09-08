"""Shut the running scene down and wait for the JVM to actually be gone.

The server route answers ``{"ok": true, "accepted": true}`` the instant it schedules the exit,
which is a lie by omission: the process is still holding its port, its GL context and its
profiler output for another second or two. Callers papered over that with ``pgrep`` loops that
had to be written to avoid matching the calling shell — so this command does the waiting.

Usage:
    uv run ixdar-cli shutdown
    uv run ixdar-cli shutdown --timeout 60
"""

import os
import time

from ..automation_client import AutomationClient, port_file_path, read_port_file
from ..cli_registry import CliCommandResult, cli_command

POLL_SECONDS = 0.2

DEFAULT_SHUTDOWN_TIMEOUT = 30.0


def process_alive(pid: int) -> bool:
    """Report whether a process id still names a live process.

    :param pid: Process id read from the port file.
    :return: True while the process exists.
    """
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


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

    The process id comes from this checkout's ``tmp/automation.port``, so nothing has to match
    a JVM by command line — the idiom that used to kill the caller's own shell.

    :param timeout: Seconds to wait for the process to disappear.
    """
    published = read_port_file()
    pid = published.get("pid")
    payload: dict = {"ok": True, "port": published.get("port"), "pid": pid}
    try:
        payload["accepted"] = bool(client.shutdown().get("accepted"))
    except Exception as unreachable:
        payload["accepted"] = False
        payload["error"] = f"no scene answered {client.base_url}: {unreachable}"
        payload["ok"] = not (pid and process_alive(pid))
        return CliCommandResult(payload=payload, exit_code=0 if payload["ok"] else 3)

    if not pid:
        payload["exited"] = None
        payload["note"] = ("no pid published in " + port_file_path()
                           + "; the scene was not started from this checkout")
        return CliCommandResult(payload=payload)

    exited, waited = await_exit(int(pid), timeout)
    payload["exited"] = exited
    payload["waitedSeconds"] = waited
    payload["ok"] = exited
    if not exited:
        payload["error"] = f"process {pid} still alive after {timeout}s"
    return CliCommandResult(payload=payload, exit_code=0 if exited else 1)
