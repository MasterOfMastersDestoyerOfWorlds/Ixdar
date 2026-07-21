"""Registry commands that derive their result from the /ui/state snapshot.

Pure passthrough wrappers (health, screenshot, click, hover, key, ...) are no longer defined here:
they are generated dynamically from the routes manifest (see ``server_routes.py``). Only commands
that add client-side logic over ``/ui/state`` remain.
"""

from ..automation_client import AutomationClient
from ..cli_registry import CliCommandResult, cli_command


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
