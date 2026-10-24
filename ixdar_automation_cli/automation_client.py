import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from .checkout import checkout_root

MANIFEST_PATH = Path(__file__).with_name("automation_routes.json")
LOOPBACK_HOST = "127.0.0.1"
DEFAULT_RETRIES = 3
DEFAULT_RETRY_DELAY = 1.0
DEFAULT_REQUEST_TIMEOUT = 10.0
PROGRESS_INTERVAL = 30.0

# AutomationPortFile: every scene writes tmp/automation/<pid>.json and removes it on exit. These
# records are the checkout's one registry of open scene pids: ixdar-cli writes a scene's record
# the moment it starts the JVM, marked with CLI_LAUNCHER, and the scene adds its port once bound.
RECORD_DIRECTORY_RELATIVE = os.path.join("tmp", "automation")
RECORD_SUFFIX = ".json"
# AutomationPortFile.CLI_LAUNCHER_PROPERTY: the scene copies its value into its record.
LAUNCHER_PROPERTY = "ixdar.automation.launcher"
CLI_LAUNCHER = "ixdar-cli"


def load_manifest() -> dict:
    """Load the committed routes manifest, or an empty manifest if it is absent.

    The CLI still works with only its registry commands when the manifest is missing.

    :return: The parsed ``automation_routes.json``, or ``{}``.
    """
    try:
        return json.loads(MANIFEST_PATH.read_text())
    except (OSError, ValueError):
        return {}


def records_directory(root: str = "") -> str:
    """Where the scenes of a checkout publish their records, one ``<pid>.json`` per scene.

    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: Absolute path to ``tmp/automation``.
    """
    return os.path.join(root or checkout_root(), RECORD_DIRECTORY_RELATIVE)


def read_port_file(path: str) -> dict:
    """Read one scene record, tolerating a bare port number.

    :param path: Record file to read.
    :return: ``{"port", "pid", "scene", "headless"}`` with whichever fields were present, or ``{}``.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            text = handle.read().strip()
    except OSError:
        return {}
    if not text:
        return {}
    try:
        parsed = json.loads(text)
    except ValueError:
        return {}
    if isinstance(parsed, int):
        return {"port": parsed}
    return parsed if isinstance(parsed, dict) else {}


def process_alive(pid: int) -> bool:
    """Report whether a process id still names a live process.

    :param pid: Process id read from a scene record.
    :return: True while the process exists.
    """
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def register_launch(pid: int, scene: str, headless: bool, root: str = "") -> None:
    """Record a scene JVM ixdar-cli just started, before it has bound a port.

    The scene rewrites this same ``<pid>.json`` with its port once its server is up, keeping the
    launcher mark, so the pid is in the registry for the JVM's whole life.

    :param pid: The JVM's pid.
    :param scene: Scene id or launch entry name.
    :param headless: Whether it runs off-screen.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    """
    directory = records_directory(root)
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, f"{pid}{RECORD_SUFFIX}"), "w", encoding="utf-8") as handle:
        json.dump({"pid": pid, "scene": scene, "headless": headless, "launcher": CLI_LAUNCHER},
                  handle)


def forget_scene(pid: int, root: str = "") -> None:
    """Drop a scene's record once its process is confirmed gone.

    :param pid: The exited JVM's pid.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    """
    try:
        os.remove(os.path.join(records_directory(root), f"{pid}{RECORD_SUFFIX}"))
    except FileNotFoundError:
        pass


def live_scenes(root: str = "") -> list[dict]:
    """The scenes of a checkout whose process is still alive, deleting the records of dead ones.

    A scene removes its own record when it exits; a killed one cannot, so a record whose pid is
    gone is stale and is dropped here rather than resolved to. A scene ixdar-cli started that has
    not bound its port yet is listed without ``port`` or ``baseUrl``.

    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: One record per live scene, ordered by pid, each bound one with its ``baseUrl``.
    """
    directory = records_directory(root)
    try:
        names = sorted(os.listdir(directory))
    except OSError:
        return []
    scenes = []
    for name in names:
        if not name.endswith(RECORD_SUFFIX):
            continue
        path = os.path.join(directory, name)
        record = read_port_file(path)
        pid = record.get("pid")
        if not pid or not process_alive(int(pid)):
            try:
                os.remove(path)
            except OSError:
                pass
            continue
        if record.get("port"):
            record["baseUrl"] = f"http://{LOOPBACK_HOST}:{record['port']}"
        scenes.append(record)
    return sorted(scenes, key=lambda record: record["pid"])


def describe_scene(record: dict) -> str:
    """One line naming a scene the way a refusal lists it.

    :param record: A scene record, or the bare ``port`` of a URL no record matches.
    :return: ``pid=… port=… scene=… headless|windowed|unrecorded``.
    """
    if "headless" not in record:
        kind = "unrecorded"
    else:
        kind = "headless" if record["headless"] else "windowed"
    return (f"pid={record.get('pid', '?')} port={record.get('port', '?')} "
            f"scene={record.get('scene') or '?'} {kind}")


class SceneResolutionError(ValueError):
    """No single scene answers to a command, or a live one stands where a new one would pile up;
    it carries the live scenes so the refusal lists them."""

    def __init__(self, message: str, scenes: list[dict]):
        super().__init__(message)
        self.scenes = scenes


def launched_scenes(root: str = "") -> list[dict]:
    """The live scenes ixdar-cli started in this checkout, headless or windowed, F5 windows aside.

    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: Their records, as :func:`live_scenes` lists them.
    """
    return [record for record in live_scenes(root) if record.get("launcher") == CLI_LAUNCHER]


def refuse_second_scene(command: str, root: str = "") -> None:
    """Refuse to launch a scene while ixdar-cli already has one alive in this checkout.

    A second scene beside a ``--keep-alive`` one is how scenes piled up until the machine ran out
    of memory.

    :param command: The launching command, named in the refusal.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    :raises SceneResolutionError: Naming each live scene's pid and the command that stops them.
    """
    scenes = launched_scenes(root)
    if not scenes:
        return
    raise SceneResolutionError(
        f"{command} refuses to start a second scene while this checkout has a live one: "
        + "; ".join(describe_scene(record) for record in scenes)
        + "; stop them with `ixdar-cli shutdown`", scenes)


def resolve_target(base_url: str = "", pid: int = 0, root: str = "") -> dict:
    """Pick the one scene a command talks to, refusing rather than guessing.

    An explicit ``base_url`` or ``pid`` names any scene, windowed included. With neither, only a
    headless scene counts — the kind ``ixdar-cli launch`` and ``run-scene`` start — so a window the
    user opened with F5 is never driven by accident, and two headless scenes are an ambiguity.

    :param base_url: Explicit automation URL, or empty.
    :param pid: Explicit scene process id, or 0.
    :param root: Checkout root; empty uses :func:`checkout_root`.
    :return: The scene's record with ``baseUrl``; for a URL no record matches, just ``port`` and
        ``baseUrl``.
    :raises SceneResolutionError: When no scene, or more than one, fits.
    """
    scenes = [record for record in live_scenes(root) if record.get("port")]
    listing = "; ".join(describe_scene(record) for record in scenes) or "none"
    if base_url:
        port = int(base_url.rstrip("/").rsplit(":", 1)[1])
        named = [record for record in scenes if record["port"] == port]
        return named[0] if named else {"port": port, "baseUrl": base_url}
    if pid:
        named = [record for record in scenes if record["pid"] == pid]
        if not named:
            raise SceneResolutionError(
                f"no live scene has pid {pid} in {records_directory(root)}; live scenes: {listing}",
                scenes)
        return named[0]
    headless = [record for record in scenes if record.get("headless")]
    if len(headless) == 1:
        return headless[0]
    if headless:
        raise SceneResolutionError(
            f"{len(headless)} headless scenes are running in this checkout; name one with --pid: "
            + listing, scenes)
    if scenes:
        raise SceneResolutionError(
            "no headless scene is running in this checkout, and a windowed scene is only driven "
            f"when named with --pid or --base-url: {listing}", scenes)
    raise SceneResolutionError(
        f"no scene is running in this checkout ({records_directory(root)} is empty); start one "
        "with `ixdar-cli launch \"<entry>\" --keep-alive` or `ixdar-cli run-scene --keep-alive`",
        scenes)

TOOLBAR_BUTTON_OFFSETS_X = {
    "pipe": -70.0,
    "grow": -20.0,
    "collapse": 30.0,
    "undo": None,
    "confirm": None,
}
TOOLBAR_BUTTON_Y_OFFSET = 25.0


def opengl_to_click_y(window_height: float, screen_y_opengl: float) -> float:
    return window_height - screen_y_opengl


def collect_tooltip_lines(ui_state: dict, include_trade: bool = False) -> list[str]:
    tooltips: list[str] = []
    for element in ui_state.get("textElements", []):
        element_type = element.get("type", "")
        if element_type == "tooltip" or (include_trade and element_type == "trade_tooltip"):
            for line in element.get("lines", []):
                text = str(line).strip()
                if text:
                    tooltips.append(text)
    return tooltips


def collect_trade_tooltip_lines(ui_state: dict) -> list[str]:
    tooltips: list[str] = []
    for element in ui_state.get("textElements", []):
        if element.get("type", "") != "trade_tooltip":
            continue
        for line in element.get("lines", []):
            text = str(line).strip()
            if text:
                tooltips.append(text)
    return tooltips


def toolbar_button_center(window_width: float, window_height: float, button_name: str) -> tuple[float, float]:
    if button_name == "undo":
        return 30.0, window_height - TOOLBAR_BUTTON_Y_OFFSET
    if button_name == "confirm":
        return window_width - 30.0, window_height - TOOLBAR_BUTTON_Y_OFFSET
    offset_x = TOOLBAR_BUTTON_OFFSETS_X.get(button_name, TOOLBAR_BUTTON_OFFSETS_X["pipe"])
    return (window_width / 2.0) + float(offset_x), window_height - TOOLBAR_BUTTON_Y_OFFSET


class AutomationClient:
    def __init__(
        self,
        base_url: str = "",
        retries: int = DEFAULT_RETRIES,
        retry_delay: float = DEFAULT_RETRY_DELAY,
        pid: int = 0,
    ):
        """A client for one scene, resolved on first use by :func:`resolve_target`.

        :param base_url: Explicit automation URL; empty resolves this checkout's scene.
        :param retries: Attempts per request; see :meth:`request_json` for which failures retry.
        :param retry_delay: Seconds before the first retry, doubling after each.
        :param pid: Explicit scene process id, used when ``base_url`` is empty.
        """
        self.base_url = base_url
        self.retries = retries
        self.retry_delay = retry_delay
        self.pid = pid
        self.target: dict = {}
        self.routes = {route["commandName"]: route for route in load_manifest().get("routes", [])}

    def resolve(self) -> dict:
        """Settle which scene this client talks to, once, so every request and report agree.

        Resolution is lazy so a command that launches its own scene never needs one running.

        :return: The scene record, ``baseUrl`` included.
        :raises SceneResolutionError: When no single scene fits.
        """
        if not self.target:
            self.target = resolve_target(self.base_url, self.pid)
            self.base_url = self.target["baseUrl"]
        return self.target

    def request_json(self, path: str, body: dict | None = None,
                     timeout: float = DEFAULT_REQUEST_TIMEOUT) -> dict:
        """Send one request and decode its JSON answer, never sending a POST the scene may have got twice.

        A GET is retried on connection errors and timeouts. A POST is retried only when the
        connection was refused, the one failure that proves the scene never saw it: a POST that
        timed out may still be running there (a long graph), so it is reported, not resent. A
        wait longer than the default prints the elapsed time to stderr every
        ``PROGRESS_INTERVAL`` seconds.

        :param path: Route path, e.g. ``/scene/model``.
        :param body: JSON body; ``None`` sends a GET.
        :param timeout: Seconds to wait for the answer; a route that holds the request open while
            slow work finishes declares a longer one in the manifest.
        :return: The decoded response.
        :raises urllib.error.URLError: When no answer came; for a POST that may have been
            delivered, the reason says it was not resent.
        """
        self.resolve()
        payload = None
        headers = {"Content-Type": "application/json"}
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
        started = time.monotonic()
        answered = threading.Event()

        def report_progress() -> None:
            while not answered.wait(PROGRESS_INTERVAL):
                print(f"waiting for {path}: {time.monotonic() - started:.0f} s of at most "
                      f"{timeout:.0f} s; it is sent once and never resent",
                      file=sys.stderr, flush=True)

        if timeout > DEFAULT_REQUEST_TIMEOUT:
            threading.Thread(target=report_progress, daemon=True).start()
        last_exc: Exception | None = None
        try:
            for attempt in range(self.retries):
                try:
                    req = urllib.request.Request(
                        self.base_url + path,
                        data=payload,
                        headers=headers,
                        method="POST" if body is not None else "GET",
                    )
                    with urllib.request.urlopen(req, timeout=timeout) as response:
                        return json.loads(response.read().decode("utf-8"))
                except urllib.error.HTTPError:
                    raise
                except (urllib.error.URLError, ConnectionError, TimeoutError) as exc:
                    reason = exc.reason if isinstance(exc, urllib.error.URLError) else exc
                    if body is not None and not isinstance(reason, ConnectionRefusedError):
                        raise urllib.error.URLError(
                            f"no answer from POST {path} after "
                            f"{time.monotonic() - started:.0f} s ({reason}); the scene may "
                            "still be running it, so it was not resent") from exc
                    last_exc = exc
                    if attempt < self.retries - 1:
                        time.sleep(self.retry_delay * (2 ** attempt))
            raise last_exc
        finally:
            answered.set()

    def call(self, command: str, **params) -> dict:
        """Request the route the manifest names ``command``, so no route path is copied here.

        Only the parameters passed are sent; the route applies its own defaults to the rest. The
        wait is the default plus the route's declared ``waitSeconds``.

        :param command: The route's ``commandName`` in ``automation_routes.json``, e.g. ``ui-state``.
        :param params: Body fields, each one of the route's declared parameter names.
        :return: The decoded response.
        :raises ValueError: When no route has that name or a parameter is not one it declares.
        """
        route = self.routes.get(command)
        if route is None:
            raise ValueError(f"no automation route is named {command!r} in {MANIFEST_PATH.name}")
        undeclared = sorted(set(params) - {param["name"] for param in route.get("params", [])})
        if undeclared:
            raise ValueError(f"route {command!r} declares no parameter {', '.join(undeclared)}")
        timeout = DEFAULT_REQUEST_TIMEOUT + route.get("waitSeconds", 0)
        if route["method"] == "GET":
            return self.request_json(route["path"], timeout=timeout)
        return self.request_json(route["path"], params, timeout=timeout)
