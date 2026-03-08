"""Registry-backed thin wrappers over AutomationClient methods."""

from automation_client import AutomationClient
from cli_registry import cli_command


@cli_command
def health(client: AutomationClient) -> dict:
    """Report the automation server health."""
    return client.health()


@cli_command(name="ui-state")
def ui_state(client: AutomationClient) -> dict:
    """Fetch the current UI state snapshot."""
    return client.ui_state()


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
