"""Animate a stroke sculpt: pass by pass through a chain of stroke_sculpt nodes, or round by round.

Pass-chain mode (``--dsl``): for every ``stroke_sculpt`` node, in file order, the running mesh viewer
shows the node's input, captures a multiview through the camera the node's SVG records, and the
SVG's strokes are drawn over it; then it shows the node's output under the same camera.

Rounds mode (``--rounds``): the stroke-sculpt skill snapshots its layered DSL directory to
``tmp/rounds/round<NN>/`` after every round. Each round becomes one frame: the round's DSL, its SVG
paths pointed at the snapshot's own files, is reloaded and captured, and the SVGs of the layers
whose file changed since the previous round are drawn over it and named in the caption.

With ``--reference`` each result frame also carries the chamfer to that mesh. Frames are composited
and encoded by ``ffmpeg`` (its librsvg decoder rasterises the SVG, ``drawtext`` the labels).

Usage:
    uv run ixdar-cli run-scene --scene mesh-viewer --keep-alive
    uv run ixdar-cli sculpt-animate --dsl ixdar-app/src/main/resources/dsl/stroke_sculpt_head.dsl
    uv run ixdar-cli sculpt-animate --rounds tmp/rounds
"""

import math
import os
import re
import shutil
import subprocess

from ..automation_client import AutomationClient
from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command

NODE_LINE = re.compile(r"^\s*([A-Za-z_]\w*)\s*=\s*(\w+)\s*\(")
SVG_ARGUMENT = re.compile(r"\bsvg\s*=\s*\"([^\"]+)\"")
GEOMETRY_ARGUMENT = re.compile(r"\bgeometry\s*=\s*([A-Za-z_]\w*)\.")
ROUND_DIRECTORY = re.compile(r"^round(\d+)$")
ROOT_ATTRIBUTE = r"\b{}\s*=\s*\"([^\"]*)\""
FRAME_PADDING = 0.15
DEFAULT_FIELD_OF_VIEW = 45.0
STROKE_STYLE = ("<style>polyline,polygon{fill:none;stroke:#e0301e;stroke-opacity:0.55;"
                "stroke-linecap:round;stroke-linejoin:round}polygon{fill:#e0301e;fill-opacity:0.25}"
                "[data-kind=silhouette]{stroke:#1e6ee0}[data-kind=valley]{stroke:#7a1ee0}</style>")


def _resolve_svg(path: str, root: str) -> str:
    """Find a node's SVG the way the node does: as given, then under the module's resources.

    :param path: the node's ``svg`` argument
    :param root: checkout root
    :return: the file's path
    """
    for candidate in (path, os.path.join("ixdar-app", path), os.path.join("ixdar-app", "src", "main", "resources", path)):
        full = candidate if os.path.isabs(candidate) else os.path.join(root, candidate)
        if os.path.isfile(full):
            return full
    raise FileNotFoundError(f"no SVG at {path}")


def _svg_camera(text: str) -> dict | None:
    """Read the camera a multiview template records as the multiview flags that reproduce it.

    :param text: the SVG's text
    :return: ``point`` and ``radius`` for the multiview route, or None when the root records no camera
    """
    root_tag = re.search(r"<svg\b[^>]*>", text).group()
    attribute = {name: (re.search(ROOT_ATTRIBUTE.format(name), root_tag) or [None, ""])[1]
                 for name in ("data-center", "data-distance", "data-fov", "data-cell-width", "data-cell-height")}
    if not attribute["data-center"] or not attribute["data-distance"]:
        return None
    half_fov = math.radians(float(attribute["data-fov"] or DEFAULT_FIELD_OF_VIEW)) / 2
    aspect = float(attribute["data-cell-width"] or 1) / float(attribute["data-cell-height"] or 1)
    narrow = min(half_fov, math.atan(math.tan(half_fov) * aspect))
    return {"point": ",".join(attribute["data-center"].split()),
            "radius": float(attribute["data-distance"]) * math.sin(narrow) / (1 + FRAME_PADDING)}


def _styled_overlay(text: str, path: str) -> str:
    """Write a copy of an SVG whose strokes are coloured by kind, for drawing over a capture.

    :param text: the SVG's text
    :param path: file written
    :return: ``path``
    """
    root_tag = re.search(r"<svg\b[^>]*>", text).group()
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text.replace(root_tag, root_tag + STROKE_STYLE, 1))
    return path


def _labelled(ffmpeg: str, image: str, overlays: list[str], label: str, out: str, width: int) -> None:
    """Scale a capture to ``width``, lay SVGs over it, and print a label on top.

    :param ffmpeg: ffmpeg executable
    :param image: multiview PNG
    :param overlays: SVGs rasterised over it at its size, in order
    :param label: caption
    :param out: PNG written
    :param width: output width in pixels
    """
    text = label.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’")
    caption = f"drawtext=text='{text}':fontcolor=black:fontsize={max(16, width // 60)}:x=20:y=20:box=1:boxcolor=white@0.7"
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", image]
    chain = []
    base = "0:v"
    for number, overlay in enumerate(overlays, start=1):
        command += ["-i", overlay]
        chain.append(f"[{number}:v][{base}]scale2ref[stroke{number}][base{number}];"
                     f"[base{number}][stroke{number}]overlay[over{number}]")
        base = f"over{number}"
    chain.append(f"[{base}]scale={width}:-2,{caption}")
    subprocess.run(command + ["-filter_complex", ";".join(chain), "-frames:v", "1", out], check=True)


def _encode(ffmpeg: str, frames: list[str], out_dir: str, width: int, seconds: float) -> tuple[str, str]:
    """Encode the frames, each held ``seconds``, as animation.mp4 and animation.gif in ``out_dir``.

    :param ffmpeg: ffmpeg executable
    :param frames: frame PNGs in order
    :param out_dir: directory the frames and the animations are in
    :param width: frame width in pixels; the gif is at most 1200 wide
    :param seconds: seconds each frame is held
    :return: the mp4 and gif paths
    """
    listing = os.path.join(out_dir, "frames.txt")
    with open(listing, "w", encoding="utf-8") as handle:
        for frame in frames + frames[-1:]:
            handle.write(f"file '{os.path.basename(frame)}'\nduration {seconds}\n")
    video = os.path.join(out_dir, "animation.mp4")
    gif = os.path.join(out_dir, "animation.gif")
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-i", listing,
                    "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-pix_fmt", "yuv420p", video], check=True, cwd=out_dir)
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-i", listing,
                    "-vf", f"scale={min(width, 1200)}:-2", gif], check=True, cwd=out_dir)
    return video, gif


def _animate_rounds(client: AutomationClient, ffmpeg: str, rounds_dir: str, out_dir: str, reference: str,
                    shading: str, width: int) -> tuple[list[str], list[dict], str]:
    """Capture one frame per round snapshot, the layers that changed that round drawn over it.

    A layer is a ``stroke_sculpt`` node; it changed when its SVG's text differs from the previous
    round's or it is new. The frame is captured through the first changed layer's camera.

    :param client: automation client of a running mesh viewer
    :param ffmpeg: ffmpeg executable
    :param rounds_dir: directory holding ``round<NN>/`` snapshots, each with one .dsl
    :param out_dir: directory frames are written to
    :param reference: OBJ each round is scored against, or empty
    :param shading: multiview surface look
    :param width: frame width in pixels
    :return: frame paths, one row per round, and an error that stopped the run (empty when none)
    """
    numbered = sorted((int(match.group(1)), name) for name in os.listdir(rounds_dir)
                      if (match := ROUND_DIRECTORY.match(name)) and os.path.isdir(os.path.join(rounds_dir, name)))
    if not numbered:
        return [], [], f"{rounds_dir} holds no round<NN>/ directory"
    frames, rows = [], []
    previous: dict[str, str] = {}
    for number, name in numbered:
        snapshot = os.path.join(rounds_dir, name)
        dsl_files = sorted(entry for entry in os.listdir(snapshot) if entry.endswith(".dsl"))
        if len(dsl_files) != 1:
            return frames, rows, f"{snapshot} holds {len(dsl_files)} .dsl files; a round snapshot holds one"
        lines = open(os.path.join(snapshot, dsl_files[0]), encoding="utf-8").read().splitlines()
        layers: dict[str, str] = {}
        changed, changed_svgs = [], []
        for index, line in enumerate(lines):
            node = NODE_LINE.match(line)
            svg = SVG_ARGUMENT.search(line)
            if not node or node.group(2) != "stroke_sculpt" or not svg:
                continue
            # The DSL names its SVGs where the live layers are; the snapshot's own copies are read.
            local = os.path.join(snapshot, os.path.basename(svg.group(1)))
            if not os.path.isfile(local):
                return frames, rows, f"{snapshot} has no {os.path.basename(svg.group(1))} for layer {node.group(1)}"
            lines[index] = line.replace(svg.group(0), f'svg="{os.path.abspath(local)}"')
            text = open(local, encoding="utf-8").read()
            layers[node.group(1)] = text
            if previous.get(node.group(1)) != text:
                changed.append(node.group(1))
                changed_svgs.append(text)
        removed = [layer for layer in previous if layer not in layers]
        round_dsl = os.path.join(out_dir, f"{name}.dsl")
        with open(round_dsl, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        camera = next((found for found in map(_svg_camera, changed_svgs) if found), None)
        capture = os.path.join(out_dir, f"{name}.png")
        if camera:
            captured = client.call("multiview", path=capture, dsl=round_dsl, shading=shading,
                                   point=camera["point"], radius=camera["radius"])
        else:
            captured = client.call("multiview", path=capture, dsl=round_dsl, shading=shading)
        row = {"round": number, "snapshot": snapshot, "changed": changed, "removed": removed}
        if not captured.get("ok", False):
            rows.append(row)
            return frames, rows, f"{name}: {captured.get('error', captured)}"
        label = f"round {number}: " + (f"changed {', '.join(changed)}" if changed else "no layer changed")
        if removed:
            label += f"; removed {', '.join(removed)}"
        if reference:
            compared = client.call("mesh-compare", reference=os.path.abspath(reference), normalize=True)
            row["chamfer"] = compared.get("chamfer_distance")
            row["hausdorff"] = compared.get("hausdorff_distance")
            label += f"  chamfer {row['chamfer']:.4f}  hausdorff {row['hausdorff']:.4f}"
        overlays = [_styled_overlay(text, os.path.join(out_dir, f"{name}_{layer}.svg"))
                    for layer, text in zip(changed, changed_svgs)]
        frame = os.path.join(out_dir, f"frame{len(frames):03d}.png")
        _labelled(ffmpeg, capture, overlays, label, frame, width)
        frames.append(frame)
        rows.append(row)
        previous = layers
    return frames, rows, ""


@cli_command(name="sculpt-animate")
def sculpt_animate(
    client: AutomationClient,
    dsl: str = "",
    rounds: str = "",
    out: str = "tmp/sculpt-animate",
    reference: str = "",
    shading: str = "curvature",
    width: int = 1600,
    seconds: float = 1.5,
) -> CliCommandResult:
    """Animate a stroke sculpt, pass by pass through a DSL's stroke_sculpt chain or round by round.

    Needs a running mesh viewer (``run-scene --scene mesh-viewer --keep-alive``). Give ``--dsl``
    for a plain chain of passes (each pass's SVG over its input, then its output), or ``--rounds``
    for the stroke-sculpt skill's ``tmp/rounds/round<NN>/`` snapshots (one frame per round, the
    layers whose SVG changed that round drawn over it and named; the frame is captured through the
    first changed layer's camera, so another changed layer captured from a different camera can sit
    slightly off). Writes the frames and animation.mp4/gif under ``--out``.

    :param dsl: Path of a DSL whose stroke_sculpt nodes are stepped through as a chain of passes.
    :param rounds: Directory of round<NN>/ snapshots of a layered DSL, each holding one .dsl and its SVGs.
    :param out: Directory for the frames, the DSLs captured and animation.mp4/gif.
    :param reference: Optional OBJ each result is scored against (mesh-compare --normalize).
    :param shading: Multiview surface look: default, clay or curvature.
    :param width: Frame width in pixels.
    :param seconds: Seconds each frame is held.
    """
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        return CliCommandResult(payload={"ok": False, "error": "ffmpeg is not on PATH"}, exit_code=2)
    if bool(dsl) == bool(rounds):
        return CliCommandResult(payload={"ok": False, "error": "give --dsl (a pass chain) or --rounds, not both"},
                                exit_code=2)
    root = str(checkout_root())
    out_dir = out if os.path.isabs(out) else os.path.join(root, out)
    os.makedirs(out_dir, exist_ok=True)
    if rounds:
        rounds_dir = rounds if os.path.isabs(rounds) else os.path.join(root, rounds)
        frames, rows, error = _animate_rounds(client, ffmpeg, rounds_dir, out_dir, reference, shading, width)
        if error:
            return CliCommandResult(payload={"ok": False, "error": error, "rounds": rows}, exit_code=3)
        video, gif = _encode(ffmpeg, frames, out_dir, width, seconds)
        return CliCommandResult(payload={"ok": True, "rounds": rows, "frames": len(frames), "mp4": video, "gif": gif})

    dsl_path = dsl if os.path.isabs(dsl) else os.path.join(root, dsl)
    lines = open(dsl_path, encoding="utf-8").read().splitlines()
    line_of = {}
    passes = []
    for index, line in enumerate(lines):
        match = NODE_LINE.match(line)
        if not match:
            continue
        line_of[match.group(1)] = index
        svg = SVG_ARGUMENT.search(line)
        source = GEOMETRY_ARGUMENT.search(line)
        if match.group(2) == "stroke_sculpt" and svg and source:
            passes.append((match.group(1), source.group(1), svg.group(1), index))
    if not passes:
        return CliCommandResult(payload={"ok": False, "error": f"{dsl} has no stroke_sculpt node with an svg"},
                                exit_code=2)

    frames = []
    rows = []
    for number, (node, source, svg, index) in enumerate(passes, start=1):
        text = open(_resolve_svg(svg, root), encoding="utf-8").read()
        camera = _svg_camera(text)
        if camera is None:
            return CliCommandResult(payload={"ok": False, "error": f"{svg} records no camera"}, exit_code=2)
        overlay = _styled_overlay(text, os.path.join(out_dir, f"pass{number}_{node}.svg"))
        row = {"pass": number, "node": node, "svg": svg}
        for stage, last_line in (("in", line_of.get(source, index - 1)), ("out", index)):
            prefix = os.path.join(out_dir, f"pass{number}_{stage}.dsl")
            with open(prefix, "w", encoding="utf-8") as handle:
                handle.write("\n".join(lines[:last_line + 1]) + "\n")
            capture = os.path.join(out_dir, f"pass{number}_{stage}.png")
            captured = client.call("multiview", path=capture, dsl=prefix, shading=shading,
                                   point=camera["point"], radius=camera["radius"])
            if not captured.get("ok", False):
                return CliCommandResult(payload={"ok": False, "error": captured.get("error", captured), "pass": row},
                                        exit_code=3)
            label = f"pass {number} ({node}) strokes on its input" if stage == "in" else f"pass {number} ({node}) result"
            if stage == "out" and reference:
                compared = client.call("mesh-compare", reference=os.path.abspath(reference), normalize=True)
                row["chamfer"] = compared.get("chamfer_distance")
                row["hausdorff"] = compared.get("hausdorff_distance")
                label += f"  chamfer {row['chamfer']:.4f}  hausdorff {row['hausdorff']:.4f}"
            frame = os.path.join(out_dir, f"frame{len(frames):03d}.png")
            _labelled(ffmpeg, capture, [overlay] if stage == "in" else [], label, frame, width)
            frames.append(frame)
        rows.append(row)

    video, gif = _encode(ffmpeg, frames, out_dir, width, seconds)
    return CliCommandResult(payload={"ok": True, "passes": rows, "frames": len(frames), "mp4": video, "gif": gif})
