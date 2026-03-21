"""Registry-backed scenario helpers for higher-level automation flows."""

import os
from typing import Literal

try:
    from ..automation_client import (
        AutomationClient,
        collect_tooltip_lines,
        collect_trade_tooltip_lines,
        toolbar_button_center,
    )
    from ..cli_registry import CliCommandResult, cli_command
    from ..quilt_mesh_fingerprint import ALGORITHM_ID, sha256_hex_from_obj_path
    from ..trade_scenarios import click_until_scene_transition, start_new_game
except ImportError:
    from automation_client import (
        AutomationClient,
        collect_tooltip_lines,
        collect_trade_tooltip_lines,
        toolbar_button_center,
    )
    from cli_registry import CliCommandResult, cli_command
    from quilt_mesh_fingerprint import ALGORITHM_ID, sha256_hex_from_obj_path
    from trade_scenarios import click_until_scene_transition, start_new_game


def _scan_trade_toolbar_tooltip(
    client: AutomationClient,
    expected_text: str,
    toolbar_x: float | None,
    toolbar_y: float | None,
    toolbar_button: str,
    button: int,
) -> dict:
    state = client.ui_state()
    window_height = float(state.get("windowHeight", 0))
    window_width = float(state.get("windowWidth", 0))
    if toolbar_x is None:
        toolbar_x, _ = toolbar_button_center(window_width, window_height, toolbar_button)
    if toolbar_y is None:
        _, toolbar_y = toolbar_button_center(window_width, window_height, toolbar_button)
    trade = state.get("trade", {})
    city_clicks: list[tuple[float, float]] = []
    if isinstance(trade, dict):
        for city in trade.get("cities", []):
            x_value = city.get("xPx")
            y_value = city.get("yPx")
            if x_value is None or y_value is None:
                continue
            click_y = (window_height - float(y_value)) if window_height else float(y_value)
            city_clicks.append((float(x_value), click_y))

    if not city_clicks:
        city_clicks = [(x_value, y_value) for x_value in range(70, 681, 60) for y_value in range(90, 651, 60)]

    for x_value, y_value in city_clicks:
        client.click(x_value, y_value, normalized=False, button=button)
        client.hover(toolbar_x, toolbar_y, normalized=False, persistent=True)
        state = client.ui_state()
        tips = collect_trade_tooltip_lines(state)
        active_tool = state.get("trade", {}).get("activeTool", "")
        if any(expected_text in tip for tip in tips):
            return {
                "ok": True,
                "hq_click": {"x": x_value, "y": y_value},
                "activeTool": active_tool,
                "trade_tooltips": tips,
            }

    state = client.ui_state()
    return {
        "ok": False,
        "activeTool": state.get("trade", {}).get("activeTool", ""),
        "textElements": state.get("textElements", []),
        "error": f"Could not find trade tooltip containing: {expected_text}",
    }


@cli_command
def probe(client: AutomationClient, out: str = "") -> dict:
    """Capture the core automation probe bundle.

    :param out: Output path for the probe screenshot file, or empty to use the server default.
    """
    health_payload = client.health()
    state = client.ui_state()
    screenshot = client.screenshot(out, inline=False)
    return {
        "ok": True,
        "health": health_payload,
        "uiStateSummary": {
            "sceneId": state.get("sceneId"),
            "sceneClass": state.get("sceneClass"),
            "mode": state.get("mode"),
            "menuVisible": state.get("menuVisible"),
            "windowWidth": state.get("windowWidth"),
            "windowHeight": state.get("windowHeight"),
        },
        "screenshot": {
            "path": screenshot.get("path"),
            "sha256": screenshot.get("sha256"),
            "width": screenshot.get("width"),
            "height": screenshot.get("height"),
        },
    }


@cli_command(name="mesh-probe")
def mesh_probe(client: AutomationClient, out: str = "") -> dict:
    """Capture the mesh-focused automation probe bundle.

    :param out: Output path for the probe screenshot file, or empty to use the server default.
    """
    health_payload = client.health()
    state = client.ui_state()
    screenshot = client.screenshot(out, inline=False)
    mesh = state.get("mesh", {})
    return {
        "ok": True,
        "health": health_payload,
        "uiStateSummary": {
            "sceneId": state.get("sceneId"),
            "sceneClass": state.get("sceneClass"),
            "mode": state.get("mode"),
            "menuVisible": state.get("menuVisible"),
            "windowWidth": state.get("windowWidth"),
            "windowHeight": state.get("windowHeight"),
        },
        "mesh": mesh,
        "screenshot": {
            "path": screenshot.get("path"),
            "sha256": screenshot.get("sha256"),
            "width": screenshot.get("width"),
            "height": screenshot.get("height"),
        },
    }


def _default_quilt_reference_obj() -> str:
    return os.path.normpath(
        os.path.join(
            os.path.dirname(__file__),
            "..",
            "..",
            "ixdar-app",
            "test",
            "resources",
            "test-meshes",
            "quilting_cube.obj",
        )
    )


@cli_command(name="quilt-mesh-compare")
def quilt_mesh_compare(client: AutomationClient, reference: str = "") -> CliCommandResult:
    """Compare mesh viewer canonical fingerprint to a reference OBJ (same algorithm as Java).

    :param reference: Path to reference OBJ, or empty to use ixdar-app/test/resources/test-meshes/quilting_cube.obj relative to the Ixdar repo root.
    """
    ref_path = reference.strip() or _default_quilt_reference_obj()
    if not os.path.isfile(ref_path):
        return CliCommandResult(
            payload={"ok": False, "error": f"Reference OBJ not found: {ref_path}"},
            exit_code=6,
        )
    live = client.mesh_fingerprint()
    if not live.get("ok", False):
        return CliCommandResult(
            payload={
                "ok": False,
                "error": live.get("error", "mesh fingerprint failed"),
                "live": live,
            },
            exit_code=6,
        )
    ref_sha = sha256_hex_from_obj_path(ref_path)
    live_sha = str(live.get("sha256", ""))
    match = ref_sha == live_sha and live.get("algorithm") == ALGORITHM_ID
    payload = {
        "ok": match,
        "algorithm": ALGORITHM_ID,
        "liveSha256": live_sha,
        "referenceSha256": ref_sha,
        "referencePath": os.path.abspath(ref_path),
        "vertexCount": live.get("vertexCount"),
        "triangleCount": live.get("triangleCount"),
    }
    if not match:
        return CliCommandResult(payload=payload, exit_code=7)
    return CliCommandResult(payload=payload)


@cli_command(name="assert-tooltip")
def assert_tooltip(
    client: AutomationClient,
    contains: list[str],
    include_trade: bool = False,
) -> CliCommandResult:
    """Assert that the visible tooltip text contains the requested strings.

    :param contains: Tooltip strings that must all appear; pass the flag multiple times to require more than one.
    :param include_trade: Include trade tooltip text in the search.
    """
    ui_state_payload = client.ui_state()
    lines = collect_tooltip_lines(ui_state_payload, include_trade)
    joined = "\n".join(lines)
    missing = [needle for needle in contains if needle not in joined]
    result = {
        "ok": len(missing) == 0,
        "tooltips": lines,
        "missing": missing,
    }
    if missing:
        return CliCommandResult(payload=result, exit_code=4)
    return CliCommandResult(payload=result)


@cli_command(name="click-scan")
def click_scan(
    client: AutomationClient,
    x_values: str = "250,300,350,375,400,450,500",
    y_start: int = 120,
    y_end: int = 620,
    y_step: int = 20,
    button: int = 0,
) -> dict:
    """Click through a grid until the scene leaves the menu.

    :param x_values: Comma-separated x coordinates to scan.
    :param y_start: Inclusive starting y coordinate for the scan.
    :param y_end: Exclusive ending y coordinate for the scan.
    :param y_step: Y increment between scan attempts.
    :param button: Mouse button index to press.
    """
    parsed_x_values = [float(value) for value in x_values.split(",") if value.strip()]
    return click_until_scene_transition(
        client=client,
        x_values=parsed_x_values,
        y_start=y_start,
        y_end=y_end,
        y_step=y_step,
        button=button,
    )


@cli_command(name="start-new-game")
def start_new_game_command(
    client: AutomationClient,
    button: int = 0,
    no_fallback_scan: bool = False,
) -> dict:
    """Leave the menu by clicking Start New Game.

    :param button: Mouse button index to press.
    :param no_fallback_scan: Disable the fallback click scan when menu bounds are missing.
    """
    return start_new_game(client, button, not no_fallback_scan)


@cli_command(name="trade-hover-scan")
def trade_hover_scan(
    client: AutomationClient,
    contains: str = "Pipe (P)",
    toolbar_x: float | None = None,
    toolbar_y: float | None = None,
    toolbar_button: Literal["pipe", "grow", "collapse", "undo", "confirm"] = "pipe",
    button: int = 0,
) -> CliCommandResult:
    """Scan trade cities until the requested toolbar tooltip appears.

    :param contains: Tooltip text that must appear in the trade tooltip.
    :param toolbar_x: Explicit toolbar x coordinate in pixels, or omit to infer it.
    :param toolbar_y: Explicit toolbar y coordinate in pixels, or omit to infer it.
    :param toolbar_button: Toolbar button to hover when inferring toolbar coordinates.
    :param button: Mouse button index to press while scanning city clicks.
    """
    result = _scan_trade_toolbar_tooltip(
        client=client,
        expected_text=contains,
        toolbar_x=toolbar_x,
        toolbar_y=toolbar_y,
        toolbar_button=toolbar_button,
        button=button,
    )
    if not result.get("ok", False):
        return CliCommandResult(payload=result, exit_code=5)
    return CliCommandResult(payload=result)
