"""Shut this checkout's scenes down and wait for each JVM to actually be gone.

The ``request-exit`` route answers ``{"ok": true, "accepted": true}`` the instant it schedules the
exit, while the process still holds its port, its GL context, its heap and its profiler output for
another second or two. This command does the waiting, and kills a scene that outlives it.

With no ``--pid`` or ``--base-url`` it stops every scene ixdar-cli started in this checkout, headless
or windowed (each ``tmp/automation/<pid>.json`` record marked with the CLI launcher), so a sweep
that left two up stops both instead of refusing to pick one: that refusal is what let crawfish JVMs
pile up until the machine ran out of memory. A window the user opened with F5 is never shut down
unless it is named.

Usage:
    uv run ixdar-cli shutdown
    uv run ixdar-cli shutdown --timeout 60
    uv run ixdar-cli --pid 12345 shutdown
"""

import os
import signal
import sys
import time

from ..automation_client import (
    CLI_LAUNCHER,
    AutomationClient,
    describe_scene,
    forget_scene,
    live_scenes,
    process_alive,
)
from ..cli_registry import CliCommandResult, cli_command

POLL_SECONDS = 0.2

DEFAULT_SHUTDOWN_TIMEOUT = 30.0

KILL_WAIT_SECONDS = 5.0


def await_exit(pid: int, timeout: float) -> tuple[bool, float]:
    """Poll a process id until it is gone.

    :param pid: Process id to watch.
    :param timeout: Seconds to wait.
    :return: ``(exited, secondsWaited)``.
    """
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        if not process_alive(pid):
            return True, round(time.monotonic() - started, 1)
        time.sleep(POLL_SECONDS)
    return not process_alive(pid), round(time.monotonic() - started, 1)


@cli_command(name="shutdown")
def shutdown(
    client: AutomationClient,
    timeout: float = DEFAULT_SHUTDOWN_TIMEOUT,
) -> CliCommandResult:
    """Stop every scene ixdar-cli started in this checkout, or the one named, once each is gone.

    Every scene is asked to exit (one still starting, with no port yet, is sent SIGTERM), then
    each pid from its ``tmp/automation/`` record is watched until it disappears; one still alive
    after ``--timeout`` is killed and confirmed gone, and its record removed. Only pids recorded in
    this checkout are ever signalled, so a scene another worktree started is never touched, and
    with no target named a window the user opened with F5 is left running and listed.

    :param timeout: Seconds to wait for each process to exit before killing it.
    """
    if client.base_url or client.pid:
        targets = [client.resolve()]
        untouched = []
    else:
        scenes = live_scenes()
        targets = [record for record in scenes if record.get("launcher") == CLI_LAUNCHER]
        untouched = [describe_scene(record) for record in scenes
                     if record.get("launcher") != CLI_LAUNCHER]

    stopped = []
    for record in targets:
        entry = {"pid": record.get("pid"), "port": record.get("port"), "scene": record.get("scene")}
        if "baseUrl" not in record:
            os.kill(int(record["pid"]), signal.SIGTERM)
            entry["accepted"] = False
            entry["note"] = "still starting, so sent SIGTERM"
            stopped.append(entry)
            continue
        try:
            scene_client = AutomationClient(record["baseUrl"], retries=1)
            entry["accepted"] = bool(scene_client.call("request-exit").get("accepted"))
        except Exception as unreachable:
            entry["accepted"] = False
            entry["error"] = f"no scene answered {record['baseUrl']}: {unreachable}"
        stopped.append(entry)

    deadline = time.monotonic() + timeout
    for entry in stopped:
        pid = entry["pid"]
        if not pid:
            entry["exited"] = None
            entry["note"] = (f"no scene record names port {entry['port']}; "
                             "the scene was not started from this checkout")
            continue
        entry["exited"], entry["waitedSeconds"] = await_exit(
            int(pid), max(0.0, deadline - time.monotonic()))
        if not entry["exited"]:
            os.kill(int(pid), signal.SIGKILL)
            entry["killed"] = True
            entry["exited"], _ = await_exit(int(pid), KILL_WAIT_SECONDS)
        if entry["exited"]:
            forget_scene(int(pid))
            how = "killed after the timeout" if entry.get("killed") else "exited"
            print(f"pid {pid} ({entry['scene']}): {how}", file=sys.stderr)
        else:
            entry["error"] = f"process {pid} still alive after {timeout}s and a SIGKILL"
            print(f"pid {pid} ({entry['scene']}): STILL ALIVE", file=sys.stderr)

    ok = all(entry["exited"] is not False for entry in stopped)
    payload: dict = {"ok": ok, "stopped": stopped}
    if untouched:
        payload["leftRunning"] = untouched
        payload["note"] = "scenes ixdar-cli did not start (F5 windows) are only stopped when named with --pid"
    return CliCommandResult(payload=payload, exit_code=0 if ok else 1)
