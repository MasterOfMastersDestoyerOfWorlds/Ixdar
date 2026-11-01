"""Report what every stroke of a stroke-sculpt DSL did: its radius, depth, points lifted and dropped, and peak displacement.

The stroke_sculpt node writes a ``report`` output per layer (MESH-80); this command runs the DSL in
the mesh viewer and prints those reports, so a sculptor learns what a stroke did without trial and
error, or which strokes were dropped and why.

Usage:
    uv run ixdar-cli run-scene --scene mesh-viewer --keep-alive
    uv run ixdar-cli stroke-report --dsl ixdar-app/src/main/resources/dsl/stroke_sculpt_head.dsl --layer pass2
"""

import os

from ..automation_client import AutomationClient
from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command
from .sculpt_dsl import dsl_nodes, resolve_resource


@cli_command(name="stroke-report")
def stroke_report(client: AutomationClient, dsl: str, layer: str = "", dropped_only: bool = False) -> CliCommandResult:
    """Run a sculpt DSL and print each stroke_sculpt layer's report: per stroke its radius, depth, lifted and dropped points, vertices moved and peak displacement.

    Needs a running mesh viewer (``run-scene --scene mesh-viewer --keep-alive``); the DSL becomes its
    active model. A stroke marked DROPPED moved nothing, with the reason: no point lifted onto
    surface its cell sees face-on enough (points grazing, off-surface or out of the cell), or every
    vertex in reach is hidden from its camera. Depth and radius are the world units the stroke's
    data-depth and data-radius give; peak displacement is the largest distance it moved a vertex.

    :param dsl: Sculpt DSL file, absolute or relative to the checkout.
    :param layer: Report only this stroke_sculpt node (its statement id); default every one.
    :param dropped_only: Keep only the layer lines and the strokes marked DROPPED.
    """
    root = str(checkout_root())
    dsl_path = resolve_resource(dsl, root)
    layers = [node["id"] for node in dsl_nodes(open(dsl_path, encoding="utf-8").read().splitlines())
              if node["type"] == "stroke_sculpt" and (not layer or node["id"] == layer)]
    if not layers:
        return CliCommandResult(payload={"ok": False, "error": f"{dsl} has no stroke_sculpt node {layer}".strip()},
                                exit_code=2)
    loaded = client.call("model", path=os.path.abspath(dsl_path))
    if not loaded.get("ok", False):
        return CliCommandResult(payload={"ok": False, "error": loaded.get("error", loaded)}, exit_code=3)
    reports = {}
    for name in layers:
        inspected = client.call("node-inspect", path=name, field="report")
        text = inspected.get("value", inspected.get("report", ""))
        if not isinstance(text, str):
            text = str(text)
        lines = text.splitlines()
        if dropped_only:
            lines = lines[:1] + [line for line in lines[1:] if "DROPPED" in line]
        reports[name] = lines
    dropped = sum(1 for lines in reports.values() for line in lines[1:] if "DROPPED" in line)
    return CliCommandResult(payload={"ok": True, "dsl": dsl_path, "seconds": loaded.get("seconds"),
                                     "dropped": dropped, "layers": reports})
