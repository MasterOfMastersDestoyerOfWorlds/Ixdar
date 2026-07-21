"""Launch Ixdar mesh-viewer, optionally load a reference OBJ overlay, and capture a screenshot.

Usage:
    uv run mesh-viewer-compare --dsl hand.dsl --node palm_body --overlay ~/Blends/Hand/Hand.obj
    uv run mesh-viewer-compare --overlay ~/Blends/Hand/Hand.obj --screenshot /tmp/compare.png
    uv run mesh-viewer-compare  # just launch with default skull.dsl
"""

import json
import os
import signal
import subprocess
import sys
import time

from ..automation_client import DEFAULT_BASE_URL, AutomationClient
from ..cli_registry import CliCommandResult, cli_command

IXDAR_APP_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "ixdar-app"))


def _wait_for_server(client: AutomationClient, timeout: float = 60.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            health = client.health()
            if health.get("status") == "ok":
                return True
        except Exception:
            pass
        time.sleep(1.0)
    return False


def _wait_for_port_free(port: int, timeout: float = 5.0) -> bool:
    """Wait until the given port is no longer in use."""
    import socket
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return True
        time.sleep(0.3)
    return False


def _build_maven_args(dsl: str, node: str, port: str) -> list[str]:
    cmd = ["mvn", "-P", "mesh-viewer", "-q"]
    if dsl:
        cmd.append(f"-Dmesh.dsl={dsl}")
    if node:
        cmd.append(f"-Dmesh.node={node}")
    if port:
        cmd.append(f"-Dmesh.port={port}")
    return cmd


def run(
    dsl: str = "",
    node: str = "",
    port: str = "",
    overlay: str = "",
    screenshot: str = "",
    base_url: str = DEFAULT_BASE_URL,
    timeout: float = 60.0,
    no_launch: bool = False,
    keep_alive: bool = False,
) -> dict:
    """Launch the mesh viewer (unless already running), optionally overlay an OBJ and screenshot.

    All human-readable progress goes to stderr; the returned dict is the machine-readable result.

    :param dsl: DSL resource name (empty uses the skull.dsl default)
    :param node: final node name in the DSL graph
    :param port: final port name on the node
    :param overlay: path to a reference OBJ to overlay
    :param screenshot: output path for the screenshot; empty uses the server default
    :param base_url: automation server base URL
    :param timeout: seconds to wait for server startup
    :param no_launch: skip launching Ixdar (assume already running)
    :param keep_alive: keep Ixdar running after the screenshot
    :return: ``{"ok": True, "overlay"?: ..., "screenshot"?: ...}``
    """
    client = AutomationClient(base_url=base_url)
    proc = None

    if not no_launch:
        try:
            health = client.health()
            if health.get("status") == "ok":
                print("Killing existing Ixdar instance...", file=sys.stderr)
                client.shutdown()
                if not _wait_for_port_free(47832, timeout=5.0):
                    print("WARNING: Port 47832 still in use after 5s, proceeding anyway.", file=sys.stderr)
        except Exception:
            pass

        maven_cmd = _build_maven_args(dsl, node, port)
        print(f"Starting mesh-viewer: {' '.join(maven_cmd)}", file=sys.stderr)
        print(f"  working dir: {IXDAR_APP_DIR}", file=sys.stderr)

        proc = subprocess.Popen(
            maven_cmd,
            cwd=IXDAR_APP_DIR,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )

        print(f"Waiting for automation server (timeout={timeout}s)...", file=sys.stderr)
        if not _wait_for_server(client, timeout):
            print("ERROR: Automation server did not start in time.", file=sys.stderr)
            if proc:
                proc.terminate()
            return {"ok": False, "error": "Automation server did not start in time."}
        print("Server ready.", file=sys.stderr)
        print("Waiting for mesh to load...", file=sys.stderr)
        mesh_deadline = time.monotonic() + 30.0
        while time.monotonic() < mesh_deadline:
            try:
                state = client.ui_state()
                mesh_info = state.get("mesh", {})
                verts = mesh_info.get("vertexCount", 0)
                if verts > 0:
                    print(f"Mesh loaded: {verts} vertices, {mesh_info.get('faceCount', 0)} faces", file=sys.stderr)
                    break
            except Exception:
                pass
            time.sleep(1.0)
        else:
            print("WARNING: Mesh did not load within 30s (may be empty or DSL error).", file=sys.stderr)

    result = {"ok": True}

    if overlay:
        overlay_path = os.path.abspath(os.path.expanduser(overlay))
        print(f"Loading overlay: {overlay_path}", file=sys.stderr)
        try:
            overlay_result = client.mesh_overlay(path=overlay_path)
            result["overlay"] = overlay_result
            if not overlay_result.get("ok"):
                print(f"WARNING: overlay load returned: {overlay_result}", file=sys.stderr)
            else:
                print("Overlay loaded.", file=sys.stderr)
                time.sleep(1.0)
        except Exception as e:
            result["overlay"] = {"ok": False, "error": str(e)}
            print(f"WARNING: overlay load failed: {e}", file=sys.stderr)

    if screenshot or overlay or not no_launch:
        print("Taking screenshot...", file=sys.stderr)
        try:
            shot = client.screenshot(screenshot, inline=False)
            result["screenshot"] = shot
            print(f"Screenshot saved: {shot.get('path', '')}", file=sys.stderr)
        except Exception as e:
            result["screenshot"] = {"ok": False, "error": str(e)}
            print(f"WARNING: screenshot failed: {e}", file=sys.stderr)

    if not keep_alive and proc:
        print("Shutting down Ixdar...", file=sys.stderr)
        try:
            client.shutdown()
        except Exception:
            pass
        proc.wait(timeout=10)

    return result


@cli_command(name="mesh-viewer")
def mesh_viewer(
    client: AutomationClient,
    dsl: str = "",
    node: str = "",
    port: str = "",
    overlay: str = "",
    screenshot: str = "",
    timeout: float = 60.0,
    no_launch: bool = False,
    keep_alive: bool = False,
) -> CliCommandResult:
    """Launch the mesh viewer, optionally overlay a reference OBJ, and screenshot.

    :param dsl: DSL resource name (empty uses the skull.dsl default).
    :param node: Final node name in the DSL graph.
    :param port: Final port name on the node.
    :param overlay: Path to a reference OBJ to overlay.
    :param screenshot: Output path for the screenshot (empty uses the server default).
    :param timeout: Seconds to wait for server startup.
    :param no_launch: Skip launching Ixdar (assume it is already running).
    :param keep_alive: Keep Ixdar running after the screenshot.
    """
    payload = run(
        dsl=dsl,
        node=node,
        port=port,
        overlay=overlay,
        screenshot=screenshot,
        base_url=client.base_url,
        timeout=timeout,
        no_launch=no_launch,
        keep_alive=keep_alive,
    )
    return CliCommandResult(payload=payload, exit_code=0 if payload.get("ok") else 1)
