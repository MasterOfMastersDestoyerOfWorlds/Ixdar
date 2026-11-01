"""Convert a stroke-sculpt DSL and its SVGs to world-unit strokes (MESH-80).

Before MESH-80 a stroke's displacement was ``clamp(data-strength, -1, 1)`` (its sign overridden by
data-kind ridge/valley) times the node's ``strength`` times its ``scale`` times a hidden face-on
weight, and its falloff radius was ``stroke-width`` in view pixels (or the node's ``falloff``). Now
the stroke alone says both, in world units: ``data-depth`` and ``data-radius``. This command
rewrites every SVG a DSL's stroke_sculpt nodes read and the nodes' ``strokes=`` text to the new
attributes, and drops ``strength``, ``scale`` and ``falloff`` from those nodes.

The pixel width is converted where the old lift measured it, by the stroke-lift route on the
layer's input, which the running viewer shows layer by layer (its rows' ``strokeWidthRadius``). Silhouettes now always drag all
the way; a silhouette whose old strength times the node's was not 1 is listed under ``changed``.

Usage:
    uv run ixdar-cli run-scene --scene mesh-viewer --keep-alive
    uv run ixdar-cli sculpt-migrate --dsl ixdar-app/src/main/resources/dsl/stroke_sculpt_head.dsl
"""

import os
import re

from ..automation_client import AutomationClient
from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command
from .sculpt_dsl import dsl_nodes, resolve_resource, unquoted

COMMENT = re.compile(r"(<!--.*?-->)", re.DOTALL)
SHAPE_TAG = re.compile(r"<(polyline|polygon)\b[^>]*>")
DEFAULT_SCALE = 0.2
DEFAULT_FALLOFF = 0.4
MIGRATED_MARK = "# sculpt-migrate: strokes carry their own world-unit data-depth and data-radius (MESH-80)"


def _attribute(tag: str, name: str) -> str | None:
    """The value of one attribute of a tag.

    :param tag: the tag's text
    :param name: attribute name
    :return: its value, or None when absent
    """
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*\"([^\"]*)\"", tag)
    return match.group(1) if match else None


def _number(value: str, nodes: dict[str, dict], default: float) -> float:
    """A node argument as a number: a literal, or an ``input_float``'s default it references.

    :param value: the raw argument, or None
    :param nodes: the DSL's nodes by id
    :param default: the port's default when the argument is absent
    :return: the number
    """
    if value is None:
        return default
    source = value.split(".")[0]
    if source in nodes and nodes[source]["type"] == "input_float":
        return float(nodes[source]["arguments"].get("default", default))
    return float(value)


def _width_radii(client: AutomationClient, text: str, probe_path: str) -> list[float | None]:
    """The world radius each shape's stroke-width meant, as the stroke-lift route measures it.

    Every shape is lifted as a stroke with no effect (radius 1, depth 0, a silhouette kept a
    silhouette) onto the mesh the viewer shows, which the caller has set to the layer's input; the
    route returns each row's ``strokeWidthRadius``.

    :param client: automation client of a running mesh viewer showing the layer's input
    :param text: the layer's SVG text, before migration
    :param probe_path: file the probe SVG is written to
    :return: per shape, in document order outside comments, its radius, or None without a stroke-width
    """
    def probe(match: re.Match) -> str:
        tag = re.sub(r'\s*\b(data-strength|data-op|data-profile)\s*=\s*"[^"]*"', "", match.group())
        tag = re.sub(r'\s*\bdata-kind\s*=\s*"(ridge|valley)"', "", tag)
        return re.sub(r"^<(polyline|polygon)\b", lambda head: f'{head.group()} data-radius="1" data-depth="0"', tag)

    with open(probe_path, "w", encoding="utf-8") as handle:
        handle.write("".join(part if COMMENT.fullmatch(part) else SHAPE_TAG.sub(probe, part)
                             for part in COMMENT.split(text)))
    lifted = client.call("stroke-lift", svg=probe_path)
    if not lifted.get("ok", False):
        raise RuntimeError(f"stroke-lift of the probe failed: {lifted.get('error', lifted)}")
    return [row.get("strokeWidthRadius") for row in lifted.get("strokeRows", [])]


def _migrate_svg(text: str, strength_multiplier: float, scale: float, falloff: float,
                 radii: list[float | None]) -> tuple[str, list[str]]:
    """Rewrite one SVG's shapes to data-depth and data-radius.

    :param text: the SVG's text
    :param strength_multiplier: the reading node's strength
    :param scale: the reading node's scale, world distance per unit strength
    :param falloff: the node's falloff, the radius of a shape with no stroke-width
    :param radii: per shape, the world radius its stroke-width meant, None without one
    :return: the new text and notes on what changed meaning
    """
    multiplier = strength_multiplier * scale
    shape_index = [-1]
    notes = []

    def migrate(match: re.Match) -> str:
        tag = match.group()
        shape_index[0] += 1
        if "data-radius" in tag:
            return tag
        kind = _attribute(tag, "data-kind") or ""
        measured = radii[shape_index[0]] if shape_index[0] < len(radii) else None
        radius = measured if measured is not None else falloff
        strength = max(-1.0, min(1.0, float(_attribute(tag, "data-strength") or 1.0)))
        strength = abs(strength) if kind == "ridge" else -abs(strength) if kind == "valley" else strength
        attributes = f'data-radius="{radius:.5f}"'
        if kind == "silhouette":
            if abs(abs(strength) * strength_multiplier - 1.0) > 1e-6:
                notes.append(f"shape {shape_index[0]}: a silhouette that dragged {abs(strength) * strength_multiplier:g} "
                             "of the way now drags all the way")
        elif 'data-op="smooth"' not in tag:
            attributes += f' data-depth="{strength * multiplier:.5f}"'
        cleaned = re.sub(r'\s*\bdata-strength\s*=\s*"[^"]*"', "", tag)
        return re.sub(r"^<(polyline|polygon)\b", lambda head: f"{head.group()} {attributes}", cleaned)

    parts = COMMENT.split(text)
    return "".join(part if COMMENT.fullmatch(part) else SHAPE_TAG.sub(migrate, part) for part in parts), notes


def _migrate_strokes(text: str, multiplier: float, falloff: float) -> str:
    """Rewrite a node's strokes text: each header's strength to a world depth, a radius when it named none.

    :param text: the ``strokes`` argument's string
    :param multiplier: the node's strength times its scale
    :param falloff: the node's falloff, the radius of a stroke whose header names none
    :return: the new strokes text
    """
    strokes = []
    for stroke in text.split(";"):
        if ":" not in stroke:
            strokes.append(stroke)
            continue
        header, points = stroke.split(":", 1)
        fields = header.split()
        silhouette = "silhouette" in fields
        depth = 1.0 if silhouette else max(-1.0, min(1.0, float(fields[0]))) * multiplier
        rest = fields[1:] if any(field.startswith("r=") for field in fields) else [f"r={falloff:g}"] + fields[1:]
        strokes.append(" ".join([f"{depth:.5g}"] + rest) + ":" + points)
    return "; ".join(strokes)


@cli_command(name="sculpt-migrate")
def sculpt_migrate(client: AutomationClient, dsl: str) -> CliCommandResult:
    """Convert a stroke-sculpt DSL and its SVGs, in place, to strokes that carry their own world-unit depth and radius.

    Needs a running mesh viewer (``run-scene --scene mesh-viewer --keep-alive``). Layer by layer, in
    file order, every stroke_sculpt node's SVG shapes get ``data-depth`` (old data-strength, clamped,
    signed by its ridge/valley kind, times the node's strength and scale) and ``data-radius`` (old
    stroke-width in pixels, at the distance from the cell's eye where the shape lands on the layer's
    input, which the viewer shows for the measurement, exactly as the old lift measured it; a
    silhouette's at the orbit target as before; the node's falloff without a width), and lose
    ``data-strength``; its ``strokes=`` text is converted the same way; ``strength``, ``scale`` and
    ``falloff`` leave the node. Not carried over: the old hidden face-on weight (points 0.25 to 0.65
    face-on to their view moved less) and a silhouette's partial drag (listed under ``changed``). An
    SVG read by two nodes with different multipliers is refused. The DSL gets a first-line mark and a
    marked DSL is left alone, so running it twice changes nothing. input_float nodes that fed a
    removed port stay, unread.

    :param dsl: The DSL file whose stroke_sculpt nodes and their SVGs are converted in place.
    """
    root = str(checkout_root())
    work = os.path.join(root, "tmp", "sculpt-migrate")
    os.makedirs(work, exist_ok=True)
    dsl_path = resolve_resource(dsl, root)
    lines = open(dsl_path, encoding="utf-8").read().splitlines()
    if MIGRATED_MARK in lines:
        return CliCommandResult(payload={"ok": True, "dsl": dsl_path, "nodes": [], "changed": [],
                                         "note": "already migrated; nothing to do"})
    nodes = dsl_nodes(lines)
    by_id = {node["id"]: node for node in nodes}
    multipliers: dict[str, float] = {}
    rows, changed = [], []
    for node in nodes:
        if node["type"] != "stroke_sculpt":
            continue
        arguments = node["arguments"]
        strength = _number(arguments.get("strength"), by_id, 1.0)
        scale = _number(arguments.get("scale"), by_id, DEFAULT_SCALE)
        multiplier = strength * scale
        falloff = _number(arguments.get("falloff"), by_id, DEFAULT_FALLOFF)
        line = lines[node["line"]]
        if "strokes" in arguments:
            old = unquoted(arguments["strokes"])
            line = line.replace(old, _migrate_strokes(old, multiplier, falloff), 1)
        line = re.sub(r",\s*(strength|scale|falloff)\s*=\s*[^,()]+", "", line)
        lines[node["line"]] = line
        row = {"node": node["id"], "multiplier": round(multiplier, 6), "falloff": falloff}
        if "svg" in arguments:
            svg_path = resolve_resource(unquoted(arguments["svg"]), root)
            if svg_path in multipliers and abs(multipliers[svg_path] - multiplier) > 1e-9:
                return CliCommandResult(payload={"ok": False, "error": f"{svg_path} is read by nodes with multipliers "
                                                 f"{multipliers[svg_path]} and {multiplier}; split it first"}, exit_code=2)
            multipliers[svg_path] = multiplier
            text = open(svg_path, encoding="utf-8").read()
            # Show the layer's input, the DSL up to the node its geometry= reads (every layer before
            # it already migrated), so each shape's width is measured where it lands.
            source = re.match(r"([A-Za-z_]\w*)\.", arguments.get("geometry", ""))
            prefix_path = os.path.join(work, "input.dsl")
            with open(prefix_path, "w", encoding="utf-8") as handle:
                handle.write("\n".join(lines[:by_id[source.group(1)]["line"] + 1]) + "\n")
            shown = client.call("mesh-dsl", name=prefix_path, node=source.group(1))
            if not shown.get("ok", False):
                return CliCommandResult(payload={"ok": False, "error": f"{node['id']}'s input does not load: "
                                                 f"{shown.get('error', shown)}", "nodes": rows}, exit_code=3)
            radii = _width_radii(client, text, os.path.join(work, "probe.svg"))
            migrated, notes = _migrate_svg(text, strength, scale, falloff, radii)
            changed += [f"{node['id']}: {note}" for note in notes]
            row["svg"] = svg_path
            row["rewritten"] = migrated != text
            with open(svg_path, "w", encoding="utf-8") as handle:
                handle.write(migrated)
        rows.append(row)
    with open(dsl_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join([MIGRATED_MARK] + lines) + "\n")
    return CliCommandResult(payload={"ok": True, "dsl": dsl_path, "nodes": rows, "changed": changed})
