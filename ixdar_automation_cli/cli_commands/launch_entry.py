"""Run a ``.vscode/launch.json`` entry the way F5 runs it, headless, and report what came up.

A launch entry is the only definition of how a scene is meant to start on the desktop — its
``vmArgs`` (profiler agent included), its arguments, its working directory — and until now nothing
could exercise one except a person pressing F5. That made a launch entry the one part of a change
an agent could write but never check. This command reads the entry, launches exactly it with one
difference, the off-screen platform instead of a window, waits for the scene to report ready, and
hands back the opening log lines and a screenshot. Headless is deliberate: a window opened from an
agent session is never shown on the desktop, so it never receives frame callbacks and the run
stalls; off-screen, the same main class, arguments and JVM flags run to the same ready state, and
a crash on this path is a crash F5 would show.

Usage:
    uv run ixdar-cli launch "Mesh Node Viewer"
    uv run ixdar-cli launch "Quad Layout" --screenshot tmp/quad-layout.png
    uv run ixdar-cli launch --list-entries
"""

import json
import os
import re
import subprocess
import sys
from typing import Annotated

from ..automation_client import AutomationClient, checkout_root
from ..cli_registry import CliCommandResult, CliOption, cli_command
from ..run_logs import next_log_path, point_latest
from .run_scene import (
    ANNOTATIONS_CLASSES,
    AUTOMATION_PORT_PROPERTY,
    CLASSES_DIR,
    CLASSPATH_FILE,
    IXDAR_APP_DIR,
    LOOPBACK_HOST,
    _await_scene,
    _ensure_build,
    _terminate,
    crash_headline,
    free_port,
)

LAUNCH_JSON_RELATIVE = os.path.join(".vscode", "launch.json")

# IxdarWindow.HEADLESS_PROPERTY: the off-screen platform, which run-scene uses too.
HEADLESS_PROPERTY = "ixdar.headless"

SETTINGS_JSON_RELATIVE = os.path.join(".vscode", "settings.json")

WORKSPACE_FOLDER_TOKEN = "${workspaceFolder}"

CONFIG_TOKEN = re.compile(r"^\$\{config:(?P<setting>[^}]+)\}$")

LINE_COMMENT = re.compile(r"(^|\s)//[^\n]*")

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)

TRAILING_COMMA = re.compile(r",(\s*[}\]])")

STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')

JVM_WARNING_PREFIX = "WARNING: "

DEFAULT_LOG_LINES = 25

DEFAULT_LAUNCH_TIMEOUT = 180.0


def strip_jsonc(text: str) -> str:
    """Turn the JSONC that VS Code accepts into JSON that ``json.loads`` accepts.

    Comments are removed only outside string literals, so a URL in a value survives.

    :param text: Raw ``launch.json`` or ``settings.json`` contents.
    :return: The same document with comments and trailing commas removed.
    """
    pieces: list[str] = []
    position = 0
    for literal in STRING_LITERAL.finditer(text):
        pieces.append(_strip_comments(text[position:literal.start()]))
        pieces.append(literal.group(0))
        position = literal.end()
    pieces.append(_strip_comments(text[position:]))
    return TRAILING_COMMA.sub(r"\1", "".join(pieces))


def _strip_comments(fragment: str) -> str:
    """Remove line and block comments from a stretch of JSONC that holds no string literal.

    :param fragment: Text between two string literals.
    :return: The fragment with its comments removed.
    """
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", fragment))


def read_configurations(root: str = "") -> list[dict]:
    """Read the launch configurations of a checkout.

    :param root: Checkout root; empty uses the checkout this call was made from.
    :return: The ``configurations`` array, or ``[]`` when there is no launch.json.
    """
    path = os.path.join(root or checkout_root(), LAUNCH_JSON_RELATIVE)
    try:
        with open(path, encoding="utf-8") as handle:
            document = json.loads(strip_jsonc(handle.read()))
    except (OSError, ValueError):
        return []
    return document.get("configurations", [])


def find_configuration(configurations: list[dict], entry: str) -> dict:
    """Resolve an entry name to its configuration, exact match first then case-insensitive substring.

    :param configurations: Configurations read from launch.json.
    :param entry: The name the caller typed.
    :return: The matching configuration.
    :raises ValueError: When nothing matches, or several entries do.
    """
    for configuration in configurations:
        if configuration.get("name") == entry:
            return configuration
    needle = entry.strip().lower()
    matches = [c for c in configurations if needle and needle in c.get("name", "").lower()]
    if len(matches) == 1:
        return matches[0]
    names = ", ".join(repr(c.get("name", "")) for c in configurations)
    if not matches:
        raise ValueError(f"no launch entry matches {entry!r}; entries: {names}")
    raise ValueError(f"launch entry {entry!r} is ambiguous between: "
                     + ", ".join(repr(c.get("name", "")) for c in matches))


def _settings(root: str) -> dict:
    """Read a checkout's VS Code settings, which launch entries reference through ``${config:…}``.

    :param root: Checkout root.
    :return: The settings map, or ``{}`` when there is no settings.json.
    """
    try:
        with open(os.path.join(root, SETTINGS_JSON_RELATIVE), encoding="utf-8") as handle:
            return json.loads(strip_jsonc(handle.read()))
    except (OSError, ValueError):
        return {}


def _tokens(value) -> list[str]:
    """Normalize a launch.json field that may be written as one string or as a list.

    :param value: The raw ``args`` or ``vmArgs`` field.
    :return: One list of tokens.
    """
    if isinstance(value, list):
        return [str(item) for item in value]
    return str(value or "").split()


def resolve_vm_arguments(configuration: dict, root: str) -> list[str]:
    """Expand a launch entry's ``vmArgs`` the way VS Code does.

    ``${config:…}`` tokens are looked up in the workspace settings and dropped when unset, which
    is what VS Code effectively does with an empty profiler-args setting. ``${workspaceFolder}``
    is substituted in the setting's value too, since that is where the profiler agent path lives.

    :param configuration: The launch configuration.
    :param root: Checkout root, substituted for ``${workspaceFolder}``.
    :return: The JVM arguments, in entry order.
    """
    resolved: list[str] = []
    settings = _settings(root)
    for token in _tokens(configuration.get("vmArgs")):
        reference = CONFIG_TOKEN.match(token)
        tokens = _tokens(settings.get(reference.group("setting"), "")) if reference else [token]
        resolved.extend(one.replace(WORKSPACE_FOLDER_TOKEN, root) for one in tokens)
    return resolved


def launch_command(configuration: dict, root: str, port: int) -> list[str]:
    """Assemble the JVM command line for a launch entry.

    The classpath is this checkout's built one rather than the one the IDE computes, and the
    only arguments added are the automation port and the headless switch, so what runs is
    otherwise the entry verbatim, with its own ``vmArgs``. The headless switch comes after the
    entry's own arguments so it wins over anything the entry says.

    :param configuration: The launch configuration.
    :param root: Checkout root.
    :param port: Automation port for the scene to bind.
    :return: The full argv.
    :raises ValueError: When the entry names no main class.
    """
    main_class = configuration.get("mainClass", "")
    if not main_class:
        raise ValueError(f"launch entry {configuration.get('name', '')!r} has no mainClass")
    with open(CLASSPATH_FILE, encoding="utf-8") as handle:
        classpath = handle.read().strip()
    command = ["java", *resolve_vm_arguments(configuration, root)]
    command.append(f"-D{HEADLESS_PROPERTY}=true")
    command.append(f"-D{AUTOMATION_PORT_PROPERTY}={port}")
    command.extend([
        "-cp",
        f"{CLASSES_DIR}:{ANNOTATIONS_CLASSES}:{classpath}",
        main_class,
    ])
    command.extend(_tokens(configuration.get("args")))
    return command


def entry_scene(configuration: dict) -> str:
    """Name the scene a launch entry opens, so its log sits beside run-scene's for the same scene.

    :param configuration: The launch configuration.
    :return: The entry's first argument (the scene id IxdarWindow takes), or the entry name when
        it has no arguments.
    """
    arguments = _tokens(configuration.get("args"))
    return arguments[0] if arguments else configuration.get("name", "")


def working_directory(configuration: dict, root: str) -> str:
    """Resolve a launch entry's ``cwd``, defaulting to the app module as the entries all do.

    :param configuration: The launch configuration.
    :param root: Checkout root, substituted for ``${workspaceFolder}``.
    :return: An absolute directory.
    """
    declared = str(configuration.get("cwd", "")).replace(WORKSPACE_FOLDER_TOKEN, root)
    if not declared:
        return IXDAR_APP_DIR
    return declared if os.path.isabs(declared) else os.path.join(root, declared)


@cli_command(name="launch")
def launch(
    entry: Annotated[str, CliOption(positional=True)] = "",
    list_entries: bool = False,
    screenshot: str = "",
    timeout: float = DEFAULT_LAUNCH_TIMEOUT,
    log_lines: int = DEFAULT_LOG_LINES,
    log: str = "",
    skip_build: bool = False,
    keep_alive: bool = False,
) -> CliCommandResult:
    """Run a .vscode/launch.json entry headless, then report its first log lines and a screenshot.

    This is the F5 path, driven: same main class, args, vmArgs (profiler agent included) and
    working directory, plus an automation port so the scene can be waited on and photographed,
    and the off-screen platform in place of a window so it runs from an agent session.

    :param entry: Launch entry name, exact or a unique substring (see --list-entries).
    :param list_entries: List the launch entry names and exit.
    :param screenshot: Capture a screenshot to this path once the scene is ready.
    :param timeout: Seconds to wait for the scene to become ready.
    :param log_lines: How many opening log lines to return.
    :param log: Log path (default: next free tmp/logs/<scene>-<entry>-<n>.log; latest-<scene>.log links the newest).
    :param skip_build: Do not compile first; run whatever classes are on disk.
    :param keep_alive: Leave the scene running instead of shutting it down.
    """
    root = checkout_root()
    configurations = read_configurations(root)
    if list_entries or not entry:
        names = [configuration.get("name", "") for configuration in configurations]
        return CliCommandResult(
            payload={"ok": bool(names), "entries": names},
            exit_code=0 if entry or list_entries else 6,
        )

    configuration = find_configuration(configurations, entry)
    name = configuration.get("name", entry)
    scene = entry_scene(configuration)
    log_path = os.path.abspath(log) if log else next_log_path(scene, name, root)
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    point_latest(scene, log_path, root)
    print(f"log: {log_path}", file=sys.stderr)

    _ensure_build(skip_build)
    port = free_port()
    base_url = f"http://{LOOPBACK_HOST}:{port}"
    client = AutomationClient(base_url=base_url)
    command = launch_command(configuration, root, port)
    cwd = working_directory(configuration, root)
    print(f"Launching entry {name!r} in {cwd}", file=sys.stderr)
    print(f"  automation: {base_url}", file=sys.stderr)

    with open(log_path, "w", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        status = _await_scene(client, process, log_path, "", timeout)
        payload: dict = {
            "ok": status["ready"],
            "entry": name,
            "mainClass": configuration.get("mainClass", ""),
            "args": _tokens(configuration.get("args")),
            "cwd": cwd,
            "port": port,
            "baseUrl": base_url,
            "log": log_path,
            "waitedSeconds": status["waited"],
            "logLines": _opening_lines(log_path, log_lines),
        }
        if status.get("crash"):
            payload["crash"] = status["crash"]
            payload["error"] = crash_headline(status["crash"])
        elif not status["ready"]:
            payload["error"] = ("process exited before the scene was ready" if status["exited"]
                                else "scene did not become ready within timeout")
        if status["ready"] and screenshot:
            payload["screenshot"] = client.screenshot(out_path=os.path.abspath(screenshot))
        if keep_alive:
            payload["pid"] = process.pid
        else:
            _terminate(process, client)
    return CliCommandResult(payload=payload, exit_code=0 if payload["ok"] else 1)


def _opening_lines(log_path: str, count: int) -> list[str]:
    """Return the first lines a launched entry printed, past the JVM's own preamble.

    Every launch opens with the same block of native-access and ``sun.misc.Unsafe`` warnings; it
    says nothing about the entry and would otherwise eat the whole excerpt.

    :param log_path: Path the JVM's output was written to.
    :param count: How many lines to keep.
    :return: The opening lines, or ``[]`` when nothing was written.
    """
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            lines = [line.rstrip() for line in handle]
    except OSError:
        return []
    interesting = [line for line in lines
                   if line.strip() and not line.startswith(JVM_WARNING_PREFIX)]
    return interesting[:count]
