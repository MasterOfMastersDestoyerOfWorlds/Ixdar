"""Launch Ixdar mesh-viewer, optionally load a reference OBJ overlay, and capture a screenshot.

Usage:
    uv run mesh-viewer-compare --dsl hand.dsl --node palm_body --overlay ~/Blends/Hand/Hand.obj
    uv run mesh-viewer-compare --overlay ~/Blends/Hand/Hand.obj --screenshot /tmp/compare.png
    uv run mesh-viewer-compare  # just launch with default skull.dsl
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import time

try:
    from .automation_client import DEFAULT_BASE_URL, AutomationClient
except ImportError:
    from automation_client import DEFAULT_BASE_URL, AutomationClient

IXDAR_APP_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "ixdar-app"))


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


def _build_maven_args(dsl: str, node: str, port: str) -> list[str]:
    cmd = ["mvn", "-P", "mesh-viewer", "-q"]
    if dsl:
        cmd.append(f"-Dmesh.dsl={dsl}")
    if node:
        cmd.append(f"-Dmesh.node={node}")
    if port:
        cmd.append(f"-Dmesh.port={port}")
    return cmd


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="mesh-viewer-compare",
        description="Launch Ixdar mesh-viewer with optional overlay and screenshot.",
    )
    parser.add_argument("--dsl", default="", help="DSL resource name (e.g. hand.dsl). Empty = skull.dsl default.")
    parser.add_argument("--node", default="", help="Final node name in the DSL graph.")
    parser.add_argument("--port", default="", help="Final port name on the node (default: geometry).")
    parser.add_argument("--overlay", default="", help="Path to reference OBJ to overlay.")
    parser.add_argument("--screenshot", default="", help="Output path for screenshot. Empty = server default.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--timeout", type=float, default=60.0, help="Seconds to wait for server startup.")
    parser.add_argument("--no-launch", action="store_true", help="Skip launching Ixdar (assume already running).")
    parser.add_argument("--keep-alive", action="store_true", help="Keep Ixdar running after screenshot (default: shut down).")
    args = parser.parse_args()

    client = AutomationClient(base_url=args.base_url)
    proc = None

    if not args.no_launch:
        # Check if already running
        try:
            health = client.health()
            if health.get("status") == "ok":
                print("Ixdar automation server already running — using existing instance.", file=sys.stderr)
                args.no_launch = True
                args.keep_alive = True
        except Exception:
            pass

    if not args.no_launch:
        maven_cmd = _build_maven_args(args.dsl, args.node, args.port)
        print(f"Starting mesh-viewer: {' '.join(maven_cmd)}", file=sys.stderr)
        print(f"  working dir: {IXDAR_APP_DIR}", file=sys.stderr)

        proc = subprocess.Popen(
            maven_cmd,
            cwd=IXDAR_APP_DIR,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )

        print(f"Waiting for automation server (timeout={args.timeout}s)...", file=sys.stderr)
        if not _wait_for_server(client, args.timeout):
            print("ERROR: Automation server did not start in time.", file=sys.stderr)
            if proc:
                proc.terminate()
            return 1
        print("Server ready.", file=sys.stderr)
        # Wait for mesh to actually load (DSL executes asynchronously)
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

    if args.overlay:
        overlay_path = os.path.abspath(os.path.expanduser(args.overlay))
        print(f"Loading overlay: {overlay_path}", file=sys.stderr)
        try:
            overlay_result = client.mesh_overlay(path=overlay_path)
            result["overlay"] = overlay_result
            if not overlay_result.get("ok"):
                print(f"WARNING: overlay load returned: {overlay_result}", file=sys.stderr)
            else:
                print("Overlay loaded.", file=sys.stderr)
                time.sleep(1.0)  # let render settle
        except Exception as e:
            result["overlay"] = {"ok": False, "error": str(e)}
            print(f"WARNING: overlay load failed: {e}", file=sys.stderr)

    if args.screenshot or args.overlay or not args.no_launch:
        print("Taking screenshot...", file=sys.stderr)
        try:
            screenshot = client.screenshot(args.screenshot, inline=False)
            result["screenshot"] = screenshot
            path = screenshot.get("path", "")
            print(f"Screenshot saved: {path}", file=sys.stderr)
        except Exception as e:
            result["screenshot"] = {"ok": False, "error": str(e)}
            print(f"WARNING: screenshot failed: {e}", file=sys.stderr)

    if not args.keep_alive and proc:
        print("Shutting down Ixdar...", file=sys.stderr)
        try:
            client.shutdown()
        except Exception:
            pass
        proc.wait(timeout=10)

    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
