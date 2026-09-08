"""Build, launch, observe and shut down an Ixdar scene in one command.

Driving a scene by hand takes four separately-approved privileged invocations — ``setsid java`` to
launch, a ``curl`` health-poll loop, ``ixdar-cli shutdown``, and ``pgrep``/``kill`` to clean up — and
one of them is a trap: ``pkill -f IxdarWindow`` also matches the *invoking shell*, whose command line
contains that string, so it kills the caller. This command replaces the whole loop.

Usage:
    uv run ixdar-cli run-scene --scene embedded-tmesh
    uv run ixdar-cli run-scene --scene embedded-tmesh --profile --timeout 400 \
        --property embeddedTMesh.off=path/to/mesh.off --property embeddedTMesh.contractFail=true
    uv run ixdar-cli run-scene --scene embedded-tmesh --coverage \
        --key "C=contracted to fixed point" --key "M=flip-surface uploaded|cannot show flips"
"""

import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time

from ..async_profile import format_hot_methods
from ..automation_client import AutomationClient, read_port_file
from ..cli_registry import CliCommandResult, cli_command
from ..jacoco_coverage import DEFAULT_PACKAGE_FILTER, agent_argument, build_report, format_coverage
from ..mesh_catalog import MODEL_PROPERTY, mesh_size, resolve_mesh, resolve_scene_properties

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
IXDAR_APP_DIR = os.path.join(REPO_DIR, "ixdar-app")
POM_PATH = os.path.join(IXDAR_APP_DIR, "pom.xml")
PARENT_POM = os.path.join(REPO_DIR, "pom.xml")
ANNOTATIONS_CLASSES = os.path.join(REPO_DIR, "annotations", "target", "classes")
CLASSPATH_FILE = os.path.join(IXDAR_APP_DIR, "target", "CP")
CLASSES_DIR = os.path.join(IXDAR_APP_DIR, "target", "classes")
SOURCES_DIR = os.path.join(IXDAR_APP_DIR, "src", "main", "java")
RESOURCES_DIR = os.path.join(IXDAR_APP_DIR, "src", "main", "resources")
DEFAULT_PROFILE_PATH = os.path.join(REPO_DIR, "profile.html")
DEFAULT_COVERAGE_PATH = os.path.join(REPO_DIR, "jacoco.exec")
COVERAGE_XML_PATH = os.path.join(REPO_DIR, "target", "jacoco", "coverage.xml")
COVERAGE_HTML_DIR = os.path.join(REPO_DIR, "target", "jacoco", "html")
ASYNC_PROFILER_LIB = os.path.join(REPO_DIR, ".profiler", "libasyncProfiler")
# Where a system install of async-profiler puts its agent library, per platform. Checked after the
# ASYNC_PROFILER_LIB environment variable and the per-checkout .profiler symlink, so a fresh
# worktree profiles without running tools/link-profiler.sh first.
ASYNC_PROFILER_CANDIDATES = (
    "/opt/homebrew/lib/libasyncProfiler.dylib",
    "/usr/local/lib/libasyncProfiler.dylib",
    "/usr/lib/libasyncProfiler.so",
    "/usr/local/lib/libasyncProfiler.so",
    "/opt/async-profiler/lib/libasyncProfiler.so",
)
AUTOMATION_PORT_PROPERTY = "ixdar.automation.port"

LOOPBACK_HOST = "127.0.0.1"


def free_port() -> int:
    """Pick a loopback port nothing is listening on, so parallel worktrees never collide.

    This is only a request: if something takes the port between here and the JVM's bind, the
    scene falls back to a free one and publishes that instead, and
    :func:`_adopt_published_port` follows it.

    :return: A currently-free TCP port.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind((LOOPBACK_HOST, 0))
        return probe.getsockname()[1]


def async_profiler_library() -> str:
    """Resolve the async-profiler agent library.

    Order: the ``ASYNC_PROFILER_LIB`` environment variable, the checkout's ``.profiler`` symlink,
    then the platform install locations in :data:`ASYNC_PROFILER_CANDIDATES`.

    :return: Path to the agent library.
    :raises FileNotFoundError: When no candidate exists, with the install hint in the message.
    """
    from_env = os.environ.get("ASYNC_PROFILER_LIB", "")
    for candidate in (from_env, ASYNC_PROFILER_LIB, *ASYNC_PROFILER_CANDIDATES):
        if candidate and os.path.exists(candidate):
            return candidate
    raise FileNotFoundError(
        "async-profiler library not found; set ASYNC_PROFILER_LIB, or install it "
        "(macOS: brew install async-profiler; Linux: place libasyncProfiler.so in /usr/lib)"
    )

NAMED_KEYS = {
    "SPACE": 32, "PERIOD": 46, "COMMA": 44, "MINUS": 45, "EQUAL": 61,
    "ESCAPE": 256, "ENTER": 257, "TAB": 258, "BACKSPACE": 259,
    "UP": 265, "DOWN": 264, "LEFT": 263, "RIGHT": 262,
}

ACTION_PRESS = 1

ACTION_RELEASE = 0

DEFAULT_KEY_SETTLE_SECONDS = 3.0


def _run_maven(args: list[str], description: str, pom: str = POM_PATH) -> None:
    """Run Maven and raise on failure, so a stale build is loud rather than silent.

    :param args: Maven arguments after the executable.
    :param description: Human-readable step name for the error message.
    :param pom: POM to build, defaulting to the app module.
    """
    print(f"{description}...", file=sys.stderr)
    completed = subprocess.run(
        ["mvn", "-q", "-f", pom, *args],
        cwd=REPO_DIR,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        tail = (completed.stdout + completed.stderr).strip().splitlines()[-25:]
        raise RuntimeError(f"{description} failed:\n" + "\n".join(tail))


def sync_resources() -> list[str]:
    """Copy every resource newer on disk than its copy in ``target/classes``.

    A scene loads DSL fixtures and other resources from the classpath, so an edit under
    ``src/main/resources`` is invisible to a ``--skip-build`` run until it is copied across —
    the reason agents kept hand-running ``cp`` before every scene.

    :return: The module-relative paths that were refreshed.
    """
    copied: list[str] = []
    for directory, _subdirectories, files in os.walk(RESOURCES_DIR):
        for filename in files:
            source = os.path.join(directory, filename)
            relative = os.path.relpath(source, RESOURCES_DIR)
            target = os.path.join(CLASSES_DIR, relative)
            if os.path.exists(target) and os.path.getmtime(target) >= os.path.getmtime(source):
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(source, target)
            copied.append(relative)
    return copied


def _ensure_build(skip_build: bool) -> list[str]:
    """Build both modules and refresh the classpath file.

    ``clean`` rather than a bare ``compile``: an incremental compile reruns the annotation processors
    over only the changed sources, which prunes the generated registries and then dies in
    ``export-automation-routes``. Both modules are built because the classpath file names the
    installed ``annotations`` jar, and a stale one surfaces as a ``NoSuchMethodError`` in the scene
    that reads like a code bug.

    :param skip_build: Skip compiling and use whatever classes are on disk. The classpath file is
        still resolved when absent, since the scene cannot launch without one.
    :return: Resources copied into ``target/classes`` because the build was skipped.
    """
    synced: list[str] = []
    if skip_build:
        print("Skipping build (--skip-build).", file=sys.stderr)
        synced = sync_resources()
        if synced:
            print(f"  synced {len(synced)} resource(s) into target/classes", file=sys.stderr)
    else:
        _run_maven(["clean", "compile", "-pl", "annotations,ixdar-app"], "Building", pom=PARENT_POM)
    if not os.path.exists(CLASSPATH_FILE):
        _run_maven(
            ["dependency:build-classpath", f"-Dmdep.outputFile={CLASSPATH_FILE}"],
            "Refreshing classpath (CP)",
        )
    return synced


def _java_command(
    scene: str,
    properties: list[str],
    profile_path: str,
    profile_event: str,
    coverage_path: str,
) -> list[str]:
    """Assemble the JVM command line for a scene.

    A fresh JVM is used rather than ``exec:java`` because ``exec:java`` runs in Maven's own process,
    which would attach the profiler agent to Maven instead of the scene. The scene always runs on
    the off-screen (headless) platform so it never pops a window onto the desktop; the headless
    platform still loads textures and fonts, so screenshots render text.

    :param scene: Scene id, passed to IxdarWindow as its argument.
    :param properties: ``key=value`` system properties.
    :param profile_path: async-profiler output path, or empty to run unprofiled.
    :param profile_event: async-profiler event, e.g. ``cpu`` or ``alloc``.
    :param coverage_path: JaCoCo ``.exec`` output path, or empty to run without coverage.
    :return: The full argv.
    """
    with open(CLASSPATH_FILE, encoding="utf-8") as handle:
        classpath = handle.read().strip()
    command = ["java", "-Dixdar.headless=true"]
    if sys.platform == "darwin":
        # GLFW refuses to initialise unless main() is on the process's first thread, and the JVM
        # otherwise starts main() on a spawned one. Headless still goes through GLFW.
        command.append("-XstartOnFirstThread")
    command.extend(f"-D{prop}" for prop in properties)
    if profile_path:
        command.append(
            f"-agentpath:{async_profiler_library()}=start,event={profile_event},file={profile_path}"
        )
    if coverage_path:
        command.append(agent_argument(coverage_path))
    command.extend([
        f"-XX:ErrorFile={os.path.join(IXDAR_APP_DIR, 'target', 'hs_err_pid%p.log')}",
        "-cp",
        f"{CLASSES_DIR}:{ANNOTATIONS_CLASSES}:{classpath}",
        "ixdar.canvas.IxdarWindow",
        scene,
    ])
    return command


CRASH_MARKER = "Exception in thread \"main\""

CRASH_TRACE_LINES = 40


def _crash_trace(log_path: str) -> list[str]:
    """Return the scene's fatal stack trace from its log, or an empty list if it has not thrown.

    Only ``main`` counts. A worker thread dying is survivable and routine; the scene's own thread
    dying is the end of the run, and is the case that otherwise masquerades as a slow scene.

    :param log_path: Path the JVM's output is being written to.
    :return: The trace lines, capped, or ``[]``.
    """
    if not os.path.exists(log_path):
        return []
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        lines = handle.read().splitlines()
    for index, line in enumerate(lines):
        if line.startswith(CRASH_MARKER):
            return [entry.rstrip() for entry in lines[index:index + CRASH_TRACE_LINES]]
    return []


def _await_scene(client: AutomationClient, process: subprocess.Popen, log_path: str,
                 await_log: str, timeout: float) -> dict:
    """Wait until the scene reports ready, the log shows a marker, or the process exits.

    ``sceneReady`` is what makes this honest: the automation server starts in the ``Canvas3D``
    constructor, so the port answers while ``initGL`` is still building the scene. Treating a healthy
    port as "ready" drives — or shuts down — a half-built scene.

    A scene that throws is also watched for, because it does not otherwise look like a failure. The
    exception kills the scene's thread, not the process, and the automation server keeps answering
    health checks that report the scene as never having become ready — so a crash and a slow scene
    are indistinguishable until the timeout expires, and the caller is then told the wrong one. The
    log is the only place the truth appears, so it is scanned for a Java stack trace and the wait
    ends the moment one shows up.

    Readiness is required, not merely one of several exits: a run that returned as soon as
    ``--await-log`` matched handed back a scene too young to screenshot or to drive, which is
    what made ``--keep-alive`` callers pad every call with a sleep.

    :param client: Automation client for health polling.
    :param process: The launched JVM.
    :param log_path: Path the JVM's output is being written to.
    :param await_log: Optional regex; the wait also runs until a line matches it.
    :param timeout: Seconds to wait.
    :return: ``{"ready": bool, "exited": bool, "matched": [...], "waited": float, "crash": [...]}``
    """
    pattern = re.compile(await_log) if await_log else None
    matched: list[str] = []
    deadline = time.monotonic() + timeout
    started = time.monotonic()
    ready = False
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return {"ready": ready, "exited": True, "matched": matched, "crash": _crash_trace(log_path),
                    "waited": round(time.monotonic() - started, 1)}
        crash = _crash_trace(log_path)
        if crash:
            return {"ready": ready, "exited": False, "matched": matched, "crash": crash,
                    "waited": round(time.monotonic() - started, 1)}
        if pattern and os.path.exists(log_path):
            with open(log_path, encoding="utf-8", errors="replace") as handle:
                matched = [line.rstrip() for line in handle if pattern.search(line)]
        _adopt_published_port(client, process)
        try:
            ready = ready or bool(client.health().get("sceneReady"))
        except Exception:
            pass
        if ready and (pattern is None or matched):
            return {"ready": True, "exited": False, "matched": matched, "crash": [],
                    "waited": round(time.monotonic() - started, 1)}
        time.sleep(1.0)
    return {"ready": ready, "exited": False, "matched": matched, "crash": _crash_trace(log_path),
            "waited": round(time.monotonic() - started, 1)}


def _adopt_published_port(client: AutomationClient, process: subprocess.Popen) -> None:
    """Retarget the client at the port the scene actually bound.

    The port this command picked is only a request: another process can take it in the moment
    between the pick and the JVM's bind, and the scene then falls back to a free one. The port
    file is the authority, and its pid is what proves the file belongs to this scene rather than
    to a previous one.

    :param client: Automation client to retarget in place.
    :param process: The launched JVM, whose pid must match the published one.
    """
    published = read_port_file()
    port = published.get("port")
    if not port or published.get("pid") != process.pid:
        return
    base_url = f"http://{LOOPBACK_HOST}:{port}"
    if client.base_url != base_url:
        print(f"  scene bound {base_url} instead; following it", file=sys.stderr)
        client.base_url = base_url


def _key_code(name: str) -> int:
    """Resolve a key name to its GLFW code, matching ``ixdar.platform.input.Keys``.

    Letters and digits are their ASCII uppercase codes, which is how GLFW numbers them, so only the
    non-printing keys need a table.

    :param name: A key name (``C``, ``SPACE``), a single character, or a raw integer code.
    :return: The GLFW key code.
    :raises ValueError: When the name is not a known key.
    """
    token = name.strip().upper()
    if token.isdigit():
        return int(token)
    if token in NAMED_KEYS:
        return NAMED_KEYS[token]
    if len(token) == 1 and token.isalnum():
        return ord(token)
    raise ValueError(f"unknown key {name!r}; use a letter, a digit, a code, or one of "
                     + ", ".join(sorted(NAMED_KEYS)))


def _await_log_beyond(log_path: str, pattern: re.Pattern, offset: int,
                      process: subprocess.Popen, timeout: float) -> tuple[str, int]:
    """Wait for a regex to appear in the scene log past a byte offset.

    Matching only past the offset is what makes a repeated key honest: pressing C twice would
    otherwise re-match the first press's log line and return instantly.

    :param log_path: Path the JVM's output is being written to.
    :param pattern: Regex to look for.
    :param offset: Byte offset to start reading from.
    :param process: The launched JVM, so a dead scene ends the wait.
    :param timeout: Seconds to wait.
    :return: ``(matched line or "", new offset)``.
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if os.path.exists(log_path):
            with open(log_path, "rb") as handle:
                handle.seek(offset)
                chunk = handle.read()
            consumed = 0
            for raw_line in chunk.split(b"\n")[:-1]:
                consumed += len(raw_line) + 1
                line = raw_line.decode("utf-8", "replace")
                if pattern.search(line):
                    return line.rstrip(), offset + consumed
            offset += consumed
        if process.poll() is not None:
            return "", offset
        time.sleep(0.5)
    return "", offset


def _drive_keys(client: AutomationClient, process: subprocess.Popen, log_path: str,
                key_specs: list[str], settle: float, timeout: float) -> list[dict]:
    """Send keypresses to the ready scene, waiting for each one's work to land.

    A scene applies keypress-requested edits on its render thread, so the press returns long before
    the work finishes — a contraction on a real mesh runs for a minute. Each spec may therefore carry
    a regex the scene logs when that key's work is done; without one the command can only wait a
    fixed settle time, which on a big mesh will under-wait.

    :param client: Automation client used to synthesize the key events.
    :param process: The launched JVM.
    :param log_path: Path the JVM's output is being written to.
    :param key_specs: ``NAME`` or ``NAME=REGEX`` entries, applied in order.
    :param settle: Seconds to pause after a key that carries no regex.
    :param timeout: Seconds to wait for a key's regex before moving on.
    :return: One result dict per key, with what was matched or why it was not.
    """
    results: list[dict] = []
    offset = os.path.getsize(log_path) if os.path.exists(log_path) else 0
    for spec in key_specs:
        name, separator, expression = spec.partition("=")
        code = _key_code(name)
        print(f"  key {name.strip().upper()}"
              + (f" → awaiting /{expression}/" if separator else ""), file=sys.stderr)
        client.key(code, action=ACTION_PRESS)
        client.key(code, action=ACTION_RELEASE)
        entry: dict = {"key": name.strip().upper()}
        if separator and expression:
            matched, offset = _await_log_beyond(
                log_path, re.compile(expression), offset, process, timeout)
            entry["matched"] = matched
            if not matched:
                entry["error"] = "regex never appeared; the key's work may be unfinished"
        else:
            time.sleep(settle)
            offset = os.path.getsize(log_path) if os.path.exists(log_path) else offset
        results.append(entry)
        if process.poll() is not None:
            entry["error"] = "the scene exited while driving keys"
            break
    return results


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


CRASH_EXCEPTION_PREFIX = "Exception in thread \"main\" "

MATCHED_LINES_REPORTED = 10


def crash_headline(crash: list[str]) -> str:
    """Condense a stack trace into the one line a caller needs: what threw, and where.

    Naming the exception and its first frame inline is what removes the second round trip
    the old ``the scene threw; see crash`` forced — the log had to be opened to learn anything.

    :param crash: Trace lines as captured from the scene log.
    :return: ``"<exception>: <message> (at <frame>)"``, or ``""`` when the trace is empty.
    """
    if not crash:
        return ""
    headline = crash[0]
    if headline.startswith(CRASH_EXCEPTION_PREFIX):
        headline = headline[len(CRASH_EXCEPTION_PREFIX):]
    frame = next((line.strip() for line in crash[1:] if line.strip().startswith("at ")), "")
    return f"{headline} ({frame})" if frame else headline


def summary_line(result: dict) -> str:
    """Render the one-line digest of a run, so a caller reads a line instead of grepping JSON.

    :param result: The run result being summarized.
    :return: A single line naming the outcome, scene, mesh, port and screenshot.
    """
    parts = ["ok" if result.get("ok") else "FAILED", f"scene={result['scene']}"]
    mesh = result.get("mesh")
    if mesh:
        parts.append(f"mesh={mesh.get('name', '?')}")
        if mesh.get("vertices"):
            parts.append(f"V={mesh['vertices']} F={mesh['faces']}")
    parts.append(f"port={result.get('port', '?')}")
    screenshot = (result.get("screenshot") or {}).get("path", "")
    if screenshot:
        parts.append(f"screenshot={screenshot}")
    if result.get("error"):
        parts.append(f"error={result['error']}")
    return " ".join(parts)


def _ensure_log_directory(log_path: str) -> None:
    """Create the directory a log is about to be written into.

    :param log_path: Path the JVM's output will be written to.
    """
    directory = os.path.dirname(os.path.abspath(log_path))
    os.makedirs(directory, exist_ok=True)


def _mesh_summary(client: AutomationClient, mesh_name: str) -> dict:
    """Name and size the mesh a run was given, preferring what the scene itself loaded.

    Only the mesh viewer reports its geometry over ``ui/state``; every other scene falls back to
    the catalogued counts of the file it was handed, which is enough to tell two runs apart.

    :param client: Automation client for the ready scene.
    :param mesh_name: The name the caller asked for, or empty when the scene chose its own model.
    :return: ``{"name", "vertices", "faces"}``, or ``{}`` when neither source knows anything.
    """
    try:
        mesh = client.ui_state().get("mesh") or {}
    except Exception:
        mesh = {}
    vertices = mesh.get("vertexCount", 0)
    faces = mesh.get("faceCount", 0)
    if not vertices and mesh_name:
        vertices, faces = mesh_size(mesh_name)
    if not vertices and not mesh_name:
        return {}
    return {"name": mesh_name or "scene default", "vertices": vertices, "faces": faces}


def run(
    scene: str,
    property: list[str] | None = None,
    mesh: str = "",
    profile: bool = False,
    profile_path: str = "",
    profile_event: str = "cpu",
    coverage: bool = False,
    coverage_path: str = "",
    coverage_filter: str = DEFAULT_PACKAGE_FILTER,
    key: list[str] | None = None,
    key_settle: float = DEFAULT_KEY_SETTLE_SECONDS,
    await_log: str = "",
    timeout: float = 120.0,
    screenshot: str = "",
    multiview: str = "",
    log: str = "",
    skip_build: bool = False,
    keep_alive: bool = False,
    top: int = 25,
) -> dict:
    """Build, launch, observe and shut down a scene on a port it picks for itself.

    :return: Summary dict: outcome, scene, mesh, port, base URL, screenshot and log excerpts.
    """
    properties = resolve_scene_properties(list(property or []))
    if mesh:
        properties.append(f"{MODEL_PROPERTY}={resolve_mesh(mesh)}")
    key_specs = list(key or [])
    for spec in key_specs:
        _key_code(spec.partition("=")[0])
    resolved_profile = (profile_path or DEFAULT_PROFILE_PATH) if (profile or profile_path) else ""
    resolved_coverage = ((coverage_path or DEFAULT_COVERAGE_PATH)
                         if (coverage or coverage_path) else "")
    log_path = log or os.path.join("/tmp", f"ixdar-scene-{scene}.log")
    _ensure_log_directory(log_path)

    synced = _ensure_build(skip_build)

    port = free_port()
    base_url = f"http://{LOOPBACK_HOST}:{port}"
    properties.append(f"{AUTOMATION_PORT_PROPERTY}={port}")
    client = AutomationClient(base_url=base_url)
    command = _java_command(scene, properties, resolved_profile, profile_event,
                            resolved_coverage)
    print(f"Launching: {' '.join(command[:4])} … {scene}", file=sys.stderr)
    print(f"  log: {log_path}", file=sys.stderr)
    print(f"  automation: {base_url}", file=sys.stderr)

    with open(log_path, "w", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            command,
            cwd=IXDAR_APP_DIR,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

        status = _await_scene(client, process, log_path, await_log, timeout)
        result: dict = {
            "ok": status["ready"],
            "scene": scene,
            "port": int(client.base_url.rsplit(":", 1)[1]),
            "baseUrl": client.base_url,
            "log": log_path,
            "waitedSeconds": status["waited"],
        }
        if synced:
            result["syncedResources"] = len(synced)
        if status["matched"]:
            result["matched"] = status["matched"][-MATCHED_LINES_REPORTED:]
        if status.get("crash"):
            result["crash"] = status["crash"]
        if not status["ready"]:
            if status.get("crash"):
                result["error"] = crash_headline(status["crash"])
            elif status["exited"]:
                result["error"] = "process exited before the scene was ready"
            else:
                result["error"] = "scene did not become ready within timeout"

        if status["ready"]:
            result["mesh"] = _mesh_summary(client, mesh)
            if key_specs:
                result["keys"] = _drive_keys(
                    client, process, log_path, key_specs, key_settle, timeout)
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

    if resolved_coverage and not keep_alive:
        result["coverage"] = resolved_coverage
        if os.path.exists(resolved_coverage):
            os.makedirs(os.path.dirname(COVERAGE_XML_PATH), exist_ok=True)
            build_report([resolved_coverage], CLASSES_DIR, SOURCES_DIR,
                         COVERAGE_XML_PATH, COVERAGE_HTML_DIR)
            result["coverageHtml"] = os.path.join(COVERAGE_HTML_DIR, "index.html")
            result["coverageReport"] = format_coverage(
                COVERAGE_XML_PATH, package_filter=coverage_filter, top=top,
                html_dir=COVERAGE_HTML_DIR).splitlines()
        else:
            result["coverageError"] = "JaCoCo wrote no exec file (was the JVM shut down cleanly?)"
    result["summary"] = summary_line(result)
    return result


@cli_command(name="run-scene")
def run_scene(
    scene: str = "embedded-tmesh",
    property: list[str] | None = None,
    mesh: str = "",
    profile: bool = False,
    profile_path: str = "",
    profile_event: str = "cpu",
    coverage: bool = False,
    coverage_path: str = "",
    coverage_filter: str = DEFAULT_PACKAGE_FILTER,
    key: list[str] | None = None,
    key_settle: float = DEFAULT_KEY_SETTLE_SECONDS,
    await_log: str = "",
    timeout: float = 120.0,
    screenshot: str = "",
    multiview: str = "",
    log: str = "",
    skip_build: bool = False,
    keep_alive: bool = False,
    top: int = 25,
) -> CliCommandResult:
    """Build, launch, wait for, optionally profile and screenshot, then shut down a scene.

    The scene binds a free port of its own and publishes it to ``tmp/automation.port`` under this
    checkout, so parallel worktrees never collide and no other command needs a ``--base-url``.

    :param scene: Scene id passed to IxdarWindow (see @SceneAnnotation ids).
    :param property: Repeatable ``key=value`` JVM system property; a ``*.off`` or ``ixdar.model``
        value may be a mesh name such as ``fertility``, and a relative ``*.save`` value resolves
        against the module resources directory.
    :param mesh: Mesh name, alias or path to load as ``ixdar.model`` (see list-meshes).
    :param profile: Capture an async-profiler flame graph.
    :param profile_path: Profile output path (default: profile.html at the repo root).
    :param profile_event: async-profiler event: ``cpu`` for time, ``alloc`` to attribute GC pressure
        to allocation sites.
    :param coverage: Record JaCoCo line coverage and report which code the run never executed.
    :param coverage_path: Coverage exec output path (default: jacoco.exec at the repo root).
    :param coverage_filter: Dotted package prefix the coverage summary is restricted to.
    :param key: Repeatable ``NAME`` or ``NAME=REGEX`` keypress to send once the scene is ready; the
        regex is awaited in the scene log before the next key, since a key's work runs on the render
        thread long after the press returns.
    :param key_settle: Seconds to pause after a key that carries no regex.
    :param await_log: Regex the scene log must show before the run returns, on top of readiness.
    :param timeout: Seconds to wait for the scene to become ready.
    :param screenshot: Capture a screenshot to this path once ready.
    :param multiview: Capture an 8-angle multiview composite to this path once ready.
    :param log: Path for the scene's stdout/stderr (default: /tmp/ixdar-scene-<scene>.log).
    :param skip_build: Do not compile first; copy any resources newer than target/classes and run
        whatever classes are on disk.
    :param keep_alive: Leave the scene running, returning only once it reports ready.
    :param top: How many hot methods or partly-covered classes to report.
    """
    payload = run(
        scene=scene,
        property=property,
        mesh=mesh,
        profile=profile,
        profile_path=profile_path,
        profile_event=profile_event,
        coverage=coverage,
        coverage_path=coverage_path,
        coverage_filter=coverage_filter,
        key=key,
        key_settle=key_settle,
        await_log=await_log,
        timeout=timeout,
        screenshot=screenshot,
        multiview=multiview,
        log=log,
        skip_build=skip_build,
        keep_alive=keep_alive,
        top=top,
    )
    return CliCommandResult(payload=payload, exit_code=0 if payload.get("ok") else 1)
