import json
import os
import time
import urllib.request


DEFAULT_BASE_URL = "http://127.0.0.1:47832"
DEFAULT_RETRIES = 3
DEFAULT_RETRY_DELAY = 1.0

PACKAGE_REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
PORT_FILE_RELATIVE = os.path.join("tmp", "automation.port")
MODULE_DIRECTORY = "ixdar-app"
POM_FILE = "pom.xml"


def checkout_root() -> str:
    """The Ixdar checkout this call belongs to, so parallel worktrees never share a scene.

    The working directory wins over the installed package, which is what makes a globally
    installed CLI still talk to the worktree it is invoked from.

    :return: Absolute path to the checkout root.
    """
    for candidate in _ancestors(os.getcwd()):
        if (os.path.isfile(os.path.join(candidate, POM_FILE))
                and os.path.isdir(os.path.join(candidate, MODULE_DIRECTORY))):
            return candidate
    return PACKAGE_REPO_DIR


def _ancestors(directory: str) -> list[str]:
    """The directory and each of its parents, nearest first.

    :param directory: Starting directory.
    :return: Absolute paths from ``directory`` up to the filesystem root.
    """
    current = os.path.abspath(directory)
    chain = [current]
    while True:
        parent = os.path.dirname(current)
        if parent == current:
            return chain
        current = parent
        chain.append(current)


def port_file_path() -> str:
    """Where this checkout's scene advertises its automation port.

    :return: Absolute path to ``tmp/automation.port``.
    """
    return os.path.join(checkout_root(), PORT_FILE_RELATIVE)


def read_port_file(path: str = "") -> dict:
    """Read the port file a running scene wrote, tolerating a bare number.

    :param path: Port file to read; empty uses :func:`port_file_path`.
    :return: ``{"port": int, "pid": int}`` with whichever fields were present, or ``{}``.
    """
    try:
        with open(path or port_file_path(), encoding="utf-8") as handle:
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


def discover_base_url() -> str:
    """Resolve the automation base URL without anyone naming a port.

    :return: The URL of this checkout's scene, or :data:`DEFAULT_BASE_URL` when no scene
        has published a port file.
    """
    port = read_port_file().get("port")
    return f"http://127.0.0.1:{port}" if port else DEFAULT_BASE_URL

KEY_ENTER = 257
KEY_G = 71
KEY_P = 80
KEY_Z = 90
MOD_CTRL = 2
DEFAULT_SETTLE_FRAMES = 2

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
        base_url: str = DEFAULT_BASE_URL,
        retries: int = DEFAULT_RETRIES,
        retry_delay: float = DEFAULT_RETRY_DELAY,
    ):
        self.base_url = base_url
        self.retries = retries
        self.retry_delay = retry_delay

    def request_json(self, path: str, body: dict | None = None) -> dict:
        payload = None
        headers = {"Content-Type": "application/json"}
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
        last_exc: Exception | None = None
        for attempt in range(self.retries):
            try:
                req = urllib.request.Request(
                    self.base_url + path,
                    data=payload,
                    headers=headers,
                    method="POST" if body is not None else "GET",
                )
                with urllib.request.urlopen(req, timeout=10) as response:
                    return json.loads(response.read().decode("utf-8"))
            except (urllib.error.URLError, ConnectionError, TimeoutError) as exc:
                last_exc = exc
                if attempt < self.retries - 1:
                    time.sleep(self.retry_delay * (2 ** attempt))
        raise last_exc

    def health(self) -> dict:
        return self.request_json("/health")

    def ui_state(self) -> dict:
        return self.request_json("/ui/state")

    def mesh_fingerprint(self) -> dict:
        """Return canonical mesh SHA-256 from the mesh viewer (GET /ui/mesh/fingerprint)."""
        return self.request_json("/ui/mesh/fingerprint")

    def load_dsl(self, name: str, node: str = "", port: str = "geometry") -> dict:
        """Switch the mesh viewer to a different DSL file at runtime."""
        return self.request_json("/mesh/dsl", {"name": name, "node": node, "port": port})

    def mesh_overlay(self, path: str = "", clear: bool = False) -> dict:
        """Load or clear a reference OBJ overlay on the mesh viewer."""
        if clear:
            return self.request_json("/mesh/overlay", {"clear": True})
        return self.request_json("/mesh/overlay", {"path": path})

    def screenshot(self, out_path: str = "", inline: bool = False) -> dict:
        return self.request_json("/ui/screenshot", {"path": out_path, "inline": inline})

    def multiview(self, out_path: str = "", inline: bool = False) -> dict:
        """Capture 8-view composite (4x2 grid: front/right/back/left/top/bottom/3-4 views)."""
        return self.request_json("/ui/multiview", {"path": out_path, "inline": inline})

    def set_orbit(self, azimuth: float, elevation: float, distance: float) -> dict:
        """Set orbit camera position (radians). Waits for next frame to render."""
        return self.request_json("/ui/orbit", {"azimuth": azimuth, "elevation": elevation, "distance": distance})

    def get_orbit(self) -> dict:
        """Get current orbit camera state."""
        return self.request_json("/ui/orbit")

    def click(self, x: float, y: float, normalized: bool = False, button: int = 0) -> dict:
        return self.request_json("/input/click", {"x": x, "y": y, "normalized": normalized, "button": button})

    def hover(self, x: float, y: float, normalized: bool = False, persistent: bool = False) -> dict:
        return self.request_json(
            "/input/hover",
            {"x": x, "y": y, "normalized": normalized, "persistent": persistent},
        )

    def clear_hover(self) -> dict:
        return self.request_json("/input/hover/clear", {})

    def key(self, key: str, action: str = "tap", settle: int = DEFAULT_SETTLE_FRAMES) -> dict:
        """Deliver a named key event, e.g. ``ESCAPE``, ``GRAVE`` or ``SHIFT+P``.

        :param key: Key name with optional modifiers; raw GLFW codes are rejected by the server.
        :param action: ``tap``, ``press``, ``release`` or ``repeat``.
        :param settle: Frames to wait for after the key, so no sleep is needed before a screenshot.
        :return: The server response, carrying ``consumed`` — whether any handler took the key.
        """
        return self.request_json("/input/key", {"key": key, "action": action, "settle": settle})

    def terminal(self, line: str, settle: int = DEFAULT_SETTLE_FRAMES) -> dict:
        """Run one line in the scene terminal and return the history lines it produced.

        :param line: Command line to type and enter, arguments included.
        :param settle: Frames to wait for after the command runs.
        :return: The server response, carrying the terminal's ``response`` lines.
        """
        return self.request_json("/input/terminal", {"line": line, "settle": settle})

    def set_projection(self, orthographic: bool = True) -> dict:
        """Set projection mode: orthographic or perspective."""
        return self.request_json("/ui/projection", {"orthographic": orthographic})

    def get_projection(self) -> dict:
        """Get current projection mode."""
        return self.request_json("/ui/projection")

    def toggle_wireframe(self) -> dict:
        """Toggle wireframe mode by injecting a Z key press."""
        return self.key("Z")

    def mesh_skeleton(self, path: str, resolution: int = 128) -> dict:
        """Extract skeleton from mesh OBJ via TEASAR algorithm."""
        return self.request_json("/mesh/skeleton", {"path": path, "resolution": resolution})

    def skeleton_compare(self, generated: str, reference: str, resolution: int = 128) -> dict:
        """Compare skeletons of two meshes: extract, match branches, return errors + recommendations."""
        return self.request_json("/mesh/skeleton/compare", {"generated": generated, "reference": reference, "resolution": resolution})

    def skeleton_compare_detailed(self, generated: str, reference: str, resolution: int = 128) -> dict:
        """Compare skeletons with per-joint 3D position deltas."""
        return self.request_json("/mesh/skeleton/compare-detailed", {"generated": generated, "reference": reference, "resolution": resolution})

    def skeleton_sensitivity(self, dsl: str, reference: str, resolution: int = 128, epsilon: float = 0) -> dict:
        """Compute skeleton sensitivity: Jacobian of joint positions w.r.t. DSL parameters."""
        return self.request_json("/mesh/skeleton/sensitivity", {
            "dsl": dsl, "reference": reference, "resolution": resolution, "epsilon": epsilon
        })

    def mesh_patches_decompose(self, path: str, resolution: int = 128) -> dict:
        """Hybrid skeleton+curvature patch decomposition of a reference mesh."""
        return self.request_json("/mesh/patches/decompose", {"path": path, "resolution": resolution})

    def mesh_patches_render_multiview(self, path: str, out_path: str = "", resolution: int = 128) -> dict:
        """Render an 8-view composite PNG of a mesh with patches colored by semantic decomposition."""
        return self.request_json("/mesh/patches/render-multiview", {
            "path": path, "out_path": out_path, "resolution": resolution
        })

    def mesh_patches_render_flat_multiview(self, path: str, out_path: str = "", resolution: int = 128) -> dict:
        """Render an 8-view composite PNG with globally-unique, Lambert-free RGB per patch for VLM pixel-sampling."""
        return self.request_json("/mesh/patches/render-flat-multiview", {
            "path": path, "out_path": out_path, "resolution": resolution
        })

    def mesh_segment(self, path: str, method: str = "spatial", n_clusters: int = 6) -> dict:
        """Legacy mesh segmentation in Java: method ∈ {components, curvature, spatial}."""
        return self.request_json("/mesh/segment", {
            "path": path, "method": method, "n_clusters": n_clusters
        })

    def shutdown(self) -> dict:
        return self.request_json("/shutdown", {})
