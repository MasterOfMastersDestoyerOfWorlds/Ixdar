"""Build, launch, observe and shut down an Ixdar scene in one command.

Driving a scene by hand takes four separately-approved privileged invocations — ``setsid java`` to
launch, a ``curl`` health-poll loop, ``ixdar-cli shutdown``, and ``pgrep``/``kill`` to clean up — and
one of them is a trap: ``pkill -f IxdarWindow`` also matches the *invoking shell*, whose command line
contains that string, so it kills the caller. This command replaces the whole loop.

Usage:
    uv run ixdar-cli run-scene --scene embedded-tmesh
    uv run ixdar-cli run-scene --scene embedded-tmesh --profile --timeout 400 \
        --property embeddedTMesh.off=path/to/mesh.off --property embeddedTMesh.contractFail=true
"""

import os
import re
import signal
import subprocess
import sys
import time

from ..async_profile import format_hot_methods
from ..automation_client import DEFAULT_BASE_URL, AutomationClient
from ..cli_registry import CliCommandResult, cli_command

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
IXDAR_APP_DIR = os.path.join(REPO_DIR, "ixdar-app")
POM_PATH = os.path.join(IXDAR_APP_DIR, "pom.xml")
CLASSPATH_FILE = os.path.join(REPO_DIR, "CP")
CLASSES_DIR = os.path.join(IXDAR_APP_DIR, "target", "classes")
DEFAULT_PROFILE_PATH = os.path.join(REPO_DIR, "profile.html")
ASYNC_PROFILER_LIB = "/usr/lib/libasyncProfiler.so"
AUTOMATION_PORT = 47832


def _run_maven(args: list[str], description: str) -> None:
    """Run Maven and raise on failure, so a stale build is loud rather than silent.

    :param args: Maven arguments after the executable.
    :param description: Human-readable step name for the error message.
    """
    print(f"{description}...", file=sys.stderr)
    completed = subprocess.run(
        ["mvn", "-q", "-f", POM_PATH, *args],
        cwd=REPO_DIR,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        tail = (completed.stdout + completed.stderr).strip().splitlines()[-25:]
        raise RuntimeError(f"{description} failed:\n" + "\n".join(tail))


def _ensure_build(skip_build: bool) -> None:
    """Compile the app and refresh the classpath file when it is older than the POM.

    Running against ``target/classes`` is only trustworthy if those classes are current, so this
    compiles by default rather than assuming. The classpath file is only regenerated when the POM is
    newer, since resolving dependencies is far slower than an incremental compile.

    :param skip_build: Skip both steps and use whatever is on disk.
    """
    if skip_build:
        print("Skipping build (--skip-build).", file=sys.stderr)
        return
    if not os.path.exists(CLASSPATH_FILE) or os.path.getmtime(POM_PATH) > os.path.getmtime(CLASSPATH_FILE):
        _run_maven(
            ["dependency:build-classpath", f"-Dmdep.outputFile={CLASSPATH_FILE}"],
            "Refreshing classpath (CP)",
        )
    _run_maven(["compile"], "Compiling ixdar-app")


def _java_command(scene: str, properties: list[str], profile_path: str, headless: bool) -> list[str]:
    """Assemble the JVM command line for a scene.

    A fresh JVM is used rather than ``exec:java`` because ``exec:java`` runs in Maven's own process,
    which would attach the profiler agent to Maven instead of the scene.

    :param scene: Scene id, passed to IxdarWindow as its argument.
    :param properties: ``key=value`` system properties.
    :param profile_path: async-profiler output path, or empty to run unprofiled.
    :param headless: Run without a visible window.
    :return: The full argv.
    """
    with open(CLASSPATH_FILE, encoding="utf-8") as handle:
        classpath = handle.read().strip()
    command = ["java"]
    if headless:
        command.append("-Dixdar.headless=true")
    command.extend(f"-D{prop}" for prop in properties)
    if profile_path:
        command.append(
            f"-agentpath:{ASYNC_PROFILER_LIB}=start,event=cpu,file={profile_path}"
        )
    command.extend([
        f"-XX:ErrorFile={os.path.join(IXDAR_APP_DIR, 'target', 'hs_err_pid%p.log')}",
        "-cp",
        f"{CLASSES_DIR}:{classpath}",
        "ixdar.canvas.IxdarWindow",
        scene,
    ])
    return command


def _await_scene(client: AutomationClient, process: subprocess.Popen, log_path: str,
                 await_log: str, timeout: float) -> dict:
    """Wait until the scene reports ready, the log shows a marker, or the process exits.

    ``sceneReady`` is what makes this honest: the automation server starts in the ``Canvas3D``
    constructor, so the port answers while ``initGL`` is still building the scene. Treating a healthy
    port as "ready" drives — or shuts down — a half-built scene.

    :param client: Automation client for health polling.
    :param process: The launched JVM.
    :param log_path: Path the JVM's output is being written to.
    :param await_log: Optional regex; matching lines are returned when seen.
    :param timeout: Seconds to wait.
    :return: ``{"ready": bool, "exited": bool, "matched": [...], "waited": float}``
    """
    pattern = re.compile(await_log) if await_log else None
    matched: list[str] = []
    deadline = time.monotonic() + timeout
    started = time.monotonic()
    ready = False
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return {"ready": ready, "exited": True, "matched": matched,
                    "waited": round(time.monotonic() - started, 1)}
        if pattern and os.path.exists(log_path):
            with open(log_path, encoding="utf-8", errors="replace") as handle:
                matched = [line.rstrip() for line in handle if pattern.search(line)]
            if matched:
                return {"ready": ready, "exited": False, "matched": matched,
                        "waited": round(time.monotonic() - started, 1)}
        try:
            if client.health().get("sceneReady"):
                ready = True
                if not pattern:
                    return {"ready": True, "exited": False, "matched": matched,
                            "waited": round(time.monotonic() - started, 1)}
        except Exception:
            pass
        time.sleep(1.0)
    return {"ready": ready, "exited": False, "matched": matched,
            "waited": round(time.monotonic() - started, 1)}


def _terminate(process: subprocess.Popen, client: AutomationClient) -> None:
    """Shut the scene down cleanly, then by PID — never by name.

    async-profiler flushes its HTML at JVM exit, so an orderly shutdown is what makes a profiled run
    usable. Falls back to signalling the process group this command created, which is safe because it
    targets a captured PID rather than a command-line pattern.

    :param process: The launched JVM.
    :param client: Automation client used to request shutdown.
    """
    if process.poll() is not None:
        return
    try:
        client.shutdown()
    except Exception:
        pass
    try:
        process.wait(timeout=30)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        process.wait(timeout=10)
    except Exception:
        process.kill()


def run(
    scene: str,
    property: list[str] | None = None,
    profile: bool = False,
    profile_path: str = "",
    await_log: str = "",
    timeout: float = 120.0,
    screenshot: str = "",
    multiview: str = "",
    log: str = "",
    skip_build: bool = False,
    keep_alive: bool = False,
    headless: bool = True,
    top: int = 25,
    base_url: str = DEFAULT_BASE_URL,
) -> dict:
    """Build, launch, observe and shut down a scene.

    :return: Result dict with the log path, readiness, matched lines and profile summary.
    """
    properties = list(property or [])
    resolved_profile = (profile_path or DEFAULT_PROFILE_PATH) if (profile or profile_path) else ""
    log_path = log or os.path.join("/tmp", f"ixdar-scene-{scene}.log")

    _ensure_build(skip_build)

    client = AutomationClient(base_url=base_url)
    command = _java_command(scene, properties, resolved_profile, headless)
    print(f"Launching: {' '.join(command[:4])} … {scene}", file=sys.stderr)
    print(f"  log: {log_path}", file=sys.stderr)

    with open(log_path, "w", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            command,
            cwd=REPO_DIR,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

        status = _await_scene(client, process, log_path, await_log, timeout)
        result: dict = {
            "ok": status["ready"] or bool(status["matched"]),
            "scene": scene,
            "log": log_path,
            "waitedSeconds": status["waited"],
            "sceneReady": status["ready"],
            "processExited": status["exited"],
        }
        if status["matched"]:
            result["matched"] = status["matched"]
        if not status["ready"] and not status["matched"]:
            result["error"] = (
                "scene did not become ready within timeout"
                if not status["exited"] else "process exited before the scene was ready"
            )

        if status["ready"]:
            if screenshot:
                result["screenshot"] = client.screenshot(out_path=os.path.abspath(screenshot))
            if multiview:
                result["multiview"] = client.multiview(out_path=os.path.abspath(multiview))

        if not keep_alive:
            _terminate(process, client)
        else:
            result["pid"] = process.pid

    if resolved_profile and not keep_alive:
        result["profile"] = resolved_profile
        if os.path.exists(resolved_profile):
            result["hotMethods"] = format_hot_methods(resolved_profile, top=top)
        else:
            result["profileError"] = "async-profiler wrote no file (was the JVM shut down cleanly?)"
    return result


@cli_command(name="run-scene")
def run_scene(
    client: AutomationClient,
    scene: str = "embedded-tmesh",
    property: list[str] | None = None,
    profile: bool = False,
    profile_path: str = "",
    await_log: str = "",
    timeout: float = 120.0,
    screenshot: str = "",
    multiview: str = "",
    log: str = "",
    skip_build: bool = False,
    keep_alive: bool = False,
    headless: bool = True,
    top: int = 25,
) -> CliCommandResult:
    """Build, launch, wait for, optionally profile and screenshot, then shut down a scene.

    :param scene: Scene id passed to IxdarWindow (see @SceneAnnotation ids).
    :param property: Repeatable ``key=value`` JVM system property.
    :param profile: Capture an async-profiler CPU flame graph.
    :param profile_path: Profile output path (default: profile.html at the repo root).
    :param await_log: Regex to wait for in the scene log, in addition to readiness.
    :param timeout: Seconds to wait for the scene to become ready.
    :param screenshot: Capture a screenshot to this path once ready.
    :param multiview: Capture an 8-angle multiview composite to this path once ready.
    :param log: Path for the scene's stdout/stderr (default: /tmp/ixdar-scene-<scene>.log).
    :param skip_build: Do not compile first; run whatever classes are on disk.
    :param keep_alive: Leave the scene running instead of shutting it down.
    :param headless: Run without a visible window.
    :param top: How many hot methods to report from the profile.
    """
    payload = run(
        scene=scene,
        property=property,
        profile=profile,
        profile_path=profile_path,
        await_log=await_log,
        timeout=timeout,
        screenshot=screenshot,
        multiview=multiview,
        log=log,
        skip_build=skip_build,
        keep_alive=keep_alive,
        headless=headless,
        top=top,
        base_url=client.base_url,
    )
    return CliCommandResult(payload=payload, exit_code=0 if payload.get("ok") else 1)
