import json
import time
import urllib.request


DEFAULT_BASE_URL = "http://127.0.0.1:47832"
DEFAULT_RETRIES = 3
DEFAULT_RETRY_DELAY = 1.0

KEY_ENTER = 257
KEY_G = 71
KEY_P = 80
KEY_Z = 90
MOD_CTRL = 2

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

    def mesh_overlay(self, path: str = "", clear: bool = False) -> dict:
        """Load or clear a reference OBJ overlay on the mesh viewer."""
        if clear:
            return self.request_json("/mesh/overlay", {"clear": True})
        return self.request_json("/mesh/overlay", {"path": path})

    def screenshot(self, out_path: str = "", inline: bool = False) -> dict:
        return self.request_json("/ui/screenshot", {"path": out_path, "inline": inline})

    def click(self, x: float, y: float, normalized: bool = False, button: int = 0) -> dict:
        return self.request_json("/input/click", {"x": x, "y": y, "normalized": normalized, "button": button})

    def hover(self, x: float, y: float, normalized: bool = False, persistent: bool = False) -> dict:
        return self.request_json(
            "/input/hover",
            {"x": x, "y": y, "normalized": normalized, "persistent": persistent},
        )

    def clear_hover(self) -> dict:
        return self.request_json("/input/hover/clear", {})

    def key(self, key_code: int, action: int = 1, mods: int = 0, scancode: int = 0) -> dict:
        return self.request_json(
            "/input/key",
            {"key": key_code, "action": action, "mods": mods, "scancode": scancode},
        )

    def shutdown(self) -> dict:
        return self.request_json("/shutdown", {})
