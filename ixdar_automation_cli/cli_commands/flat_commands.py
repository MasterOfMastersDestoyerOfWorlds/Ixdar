"""Registry-backed thin wrappers over AutomationClient methods."""

try:
    from ..automation_client import AutomationClient
    from ..cli_registry import CliCommandResult, cli_command
except ImportError:
    from automation_client import AutomationClient
    from cli_registry import CliCommandResult, cli_command


@cli_command
def health(client: AutomationClient) -> dict:
    """Report the automation server health."""
    return client.health()


@cli_command(name="ui-state")
def ui_state(client: AutomationClient) -> dict:
    """Fetch the current UI state snapshot."""
    return client.ui_state()


@cli_command(name="mesh-state")
def mesh_state(client: AutomationClient) -> dict:
    """Extract the mesh viewer state from the UI snapshot."""
    ui_state_payload = client.ui_state()
    return {
        "ok": True,
        "mesh": ui_state_payload.get("mesh", {}),
    }


@cli_command(name="mesh-validate")
def mesh_validate(
    client: AutomationClient,
    expect_closed: bool = True,
    allow_degenerate_faces: bool = False,
    min_faces: int = 1,
) -> CliCommandResult:
    """Validate the current mesh viewer payload.

    :param expect_closed: Require the mesh to have zero boundary edges.
    :param allow_degenerate_faces: Permit degenerate faces in the validation result.
    :param min_faces: Minimum acceptable face count for the current mesh.
    """
    ui_state_payload = client.ui_state()
    mesh = ui_state_payload.get("mesh", {})
    vertex_count = int(mesh.get("vertexCount", 0))
    face_count = int(mesh.get("faceCount", 0))
    boundary_edge_count = int(mesh.get("boundaryEdgeCount", 0))
    degenerate_face_count = int(mesh.get("degenerateFaceCount", 0))

    checks = {
        "hasMesh": bool(mesh),
        "hasVertices": vertex_count > 0,
        "meetsMinFaces": face_count >= min_faces,
        "closed": (boundary_edge_count == 0) if expect_closed else True,
        "degenerateFaces": (degenerate_face_count == 0) if not allow_degenerate_faces else True,
    }
    ok = all(checks.values())
    result = {
        "ok": ok,
        "checks": checks,
        "mesh": mesh,
    }
    if not ok:
        return CliCommandResult(payload=result, exit_code=6)
    return CliCommandResult(payload=result)


@cli_command(name="audio-state")
def audio_state(client: AutomationClient) -> dict:
    """Extract the audio state from the UI snapshot."""
    ui_state_payload = client.ui_state()
    return {
        "ok": True,
        "audio": ui_state_payload.get("audio", {}),
    }


@cli_command(name="audio-log")
def audio_log(client: AutomationClient, tail: int = 20) -> dict:
    """Return recent audio log events.

    :param tail: Number of trailing events to include, or a negative value to include all events.
    """
    ui_state_payload = client.ui_state()
    audio = ui_state_payload.get("audio", {})
    events = audio.get("eventLog", [])
    if tail >= 0:
        events = events[-tail:]
    return {
        "ok": True,
        "count": len(events),
        "events": events,
    }


@cli_command
def screenshot(client: AutomationClient, out: str = "", inline: bool = False) -> dict:
    """Capture a screenshot through the automation server.

    :param out: Output path for the screenshot file, or empty to use the server default.
    :param inline: Include the image bytes inline in the JSON response.
    """
    return client.screenshot(out, inline=inline)


@cli_command
def click(client: AutomationClient, x: float, y: float, normalized: bool = False, button: int = 0) -> dict:
    """Send a click input event.

    :param x: Horizontal click coordinate.
    :param y: Vertical click coordinate.
    :param normalized: Treat the coordinates as normalized 0..1 values instead of pixels.
    :param button: Mouse button index to press.
    """
    return client.click(x, y, normalized=normalized, button=button)


@cli_command
def hover(client: AutomationClient, x: float, y: float, normalized: bool = False, persistent: bool = False) -> dict:
    """Move the automation hover cursor.

    :param x: Horizontal hover coordinate.
    :param y: Vertical hover coordinate.
    :param normalized: Treat the coordinates as normalized 0..1 values instead of pixels.
    :param persistent: Keep the hover active until explicitly cleared.
    """
    return client.hover(x, y, normalized=normalized, persistent=persistent)


@cli_command(name="hover-clear")
def hover_clear(client: AutomationClient) -> dict:
    """Clear any persistent automation hover state."""
    return client.clear_hover()


@cli_command
def scroll(client: AutomationClient, delta: float) -> dict:
    """Send a scroll wheel input event.

    :param delta: Scroll wheel delta to send.
    """
    return client.request_json("/input/scroll", {"delta": delta})


@cli_command
def key(client: AutomationClient, key: int, action: int = 1, mods: int = 0, scancode: int = 0) -> dict:
    """Send a keyboard key event.

    :param key: GLFW key code to send.
    :param action: GLFW action code, usually press or release.
    :param mods: GLFW modifier mask.
    :param scancode: Platform scancode override.
    """
    return client.key(key, action=action, mods=mods, scancode=scancode)


@cli_command
def drag(
    client: AutomationClient,
    start_x: float,
    start_y: float,
    end_x: float,
    end_y: float,
    normalized: bool = False,
) -> dict:
    """Send a drag gesture input event.

    :param start_x: Starting X coordinate of the drag.
    :param start_y: Starting Y coordinate of the drag.
    :param end_x: Ending X coordinate of the drag.
    :param end_y: Ending Y coordinate of the drag.
    :param normalized: Treat coordinates as normalized 0..1 values instead of pixels.
    """
    return client.drag(start_x, start_y, end_x, end_y, normalized=normalized)


@cli_command(name="drag-validate")
def drag_validate(
    client: AutomationClient,
    start_x: float,
    start_y: float,
    end_x: float,
    end_y: float,
    expected_delta_x_min: float = 0.0,
    expected_delta_y_min: float = 0.0,
    normalized: bool = False,
) -> dict:
    """Execute a drag and validate camera motion.

    :param start_x: Starting X coordinate of the drag.
    :param start_y: Starting Y coordinate of the drag.
    :param end_x: Ending X coordinate of the drag.
    :param end_y: Ending Y coordinate of the drag.
    :param expected_delta_x_min: Minimum expected horizontal delta (for validation).
    :param expected_delta_y_min: Minimum expected vertical delta (for validation).
    :param normalized: Treat coordinates as normalized 0..1 values instead of pixels.
    """
    # Capture initial camera state
    initial_state = client.ui_state()
    camera_before = initial_state.get("cameraState", {})

    # Execute the drag
    drag_result = client.drag(start_x, start_y, end_x, end_y, normalized=normalized)

    # Capture final camera state
    final_state = client.ui_state()
    camera_after = final_state.get("cameraState", {})

    # Build validation result
    result = {
        "ok": drag_result.get("ok", False),
        "drag_executed": drag_result,
        "camera_before": camera_before,
        "camera_after": camera_after,
        "delta_x": camera_after.get("mouseX", 0) - camera_before.get("mouseX", 0),
        "delta_y": camera_after.get("mouseY", 0) - camera_before.get("mouseY", 0),
    }

    # Validate expected motion
    result["validations"] = {
        "drag_succeeded": drag_result.get("ok", False),
        "mouse_moved": abs(result.get("delta_x", 0)) > 1 or abs(result.get("delta_y", 0)) > 1,
    }

    if expected_delta_x_min > 0:
        result["validations"]["delta_x_sufficient"] = result.get("delta_x", 0) >= expected_delta_x_min
    if expected_delta_y_min > 0:
        result["validations"]["delta_y_sufficient"] = result.get("delta_y", 0) >= expected_delta_y_min

    result["ok"] = all(result.get("validations", {}).values())
    return result


@cli_command(name="type")
def type_text(client: AutomationClient, text: str) -> dict:
    """Send a text input event.

    :param text: Text payload to type through the automation server.
    """
    return client.request_json("/input/type", {"text": text})


@cli_command
def shutdown(client: AutomationClient) -> dict:
    """Request a clean automation server shutdown."""
    return client.shutdown()
