"""Step through a stroke-sculpt DSL node by node and save the steps as an animation.

For every ``stroke_sculpt`` node, in file order, the running mesh viewer shows the node's input,
captures a multiview through the camera the node's SVG records, and the SVG's strokes are drawn
over it; then it shows the node's output under the same camera. With ``--reference`` each output
frame also carries the chamfer to that mesh. Frames are composited and encoded by ``ffmpeg``
(its librsvg decoder rasterises the SVG, ``drawtext`` the labels), so no Python imaging package
is needed.

Usage:
    uv run ixdar-cli run-scene --scene mesh-viewer --keep-alive
    uv run ixdar-cli sculpt-animate --dsl ixdar-app/src/main/resources/dsl/stroke_sculpt_head.dsl
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


def _labelled(ffmpeg: str, image: str, overlay: str, label: str, out: str, width: int) -> None:
    """Scale a capture to ``width``, lay an SVG over it when given, and print a label on top.

    :param ffmpeg: ffmpeg executable
    :param image: multiview PNG
    :param overlay: SVG to rasterise over it at the same size, or empty
    :param label: caption
    :param out: PNG written
    :param width: output width in pixels
    """
    text = label.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’")
    caption = f"drawtext=text='{text}':fontcolor=black:fontsize={max(16, width // 60)}:x=20:y=20:box=1:boxcolor=white@0.7"
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", image]
    if overlay:
        command += ["-i", overlay, "-filter_complex",
                    f"[1:v][0:v]scale2ref[stroke][base];[base][stroke]overlay,scale={width}:-2,{caption}"]
    else:
        command += ["-vf", f"scale={width}:-2,{caption}"]
    subprocess.run(command + ["-frames:v", "1", out], check=True)


@cli_command(name="sculpt-animate")
def sculpt_animate(
    client: AutomationClient,
    dsl: str,
    out: str = "tmp/sculpt-animate",
    reference: str = "",
    shading: str = "curvature",
    width: int = 1600,
    seconds: float = 1.5,
) -> CliCommandResult:
    """Animate a stroke-sculpt DSL node by node: each pass's SVG over its input, then its output.

    Needs a running mesh viewer (``run-scene --scene mesh-viewer --keep-alive``). Each pass is
    captured through the camera its SVG records, so the strokes line up with the surface.

    :param dsl: Path of the DSL whose stroke_sculpt nodes are stepped through.
    :param out: Directory for the frames, the per-pass DSL prefixes and animation.mp4/gif.
    :param reference: Optional OBJ each pass's output is scored against (mesh-compare --normalize).
    :param shading: Multiview surface look: default, clay or curvature.
    :param width: Frame width in pixels.
    :param seconds: Seconds each frame is held.
    """
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        return CliCommandResult(payload={"ok": False, "error": "ffmpeg is not on PATH"}, exit_code=2)
    root = str(checkout_root())
    dsl_path = dsl if os.path.isabs(dsl) else os.path.join(root, dsl)
    out_dir = out if os.path.isabs(out) else os.path.join(root, out)
    os.makedirs(out_dir, exist_ok=True)
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
        svg_file = _resolve_svg(svg, root)
        text = open(svg_file, encoding="utf-8").read()
        root_tag = re.search(r"<svg\b[^>]*>", text).group()
        attribute = {name: (re.search(ROOT_ATTRIBUTE.format(name), root_tag) or [None, ""])[1]
                     for name in ("data-center", "data-distance", "data-fov", "data-cell-width", "data-cell-height")}
        if not attribute["data-center"] or not attribute["data-distance"]:
            return CliCommandResult(payload={"ok": False, "error": f"{svg} records no camera"}, exit_code=2)
        center = ",".join(attribute["data-center"].split())
        half_fov = math.radians(float(attribute["data-fov"] or DEFAULT_FIELD_OF_VIEW)) / 2
        aspect = float(attribute["data-cell-width"] or 1) / float(attribute["data-cell-height"] or 1)
        narrow = min(half_fov, math.atan(math.tan(half_fov) * aspect))
        radius = float(attribute["data-distance"]) * math.sin(narrow) / (1 + FRAME_PADDING)
        overlay = os.path.join(out_dir, f"pass{number}_{node}.svg")
        with open(overlay, "w", encoding="utf-8") as handle:
            handle.write(text.replace(root_tag, root_tag + STROKE_STYLE, 1))
        row = {"pass": number, "node": node, "svg": svg}
        for stage, last_line in (("in", line_of.get(source, index - 1)), ("out", index)):
            prefix = os.path.join(out_dir, f"pass{number}_{stage}.dsl")
            with open(prefix, "w", encoding="utf-8") as handle:
                handle.write("\n".join(lines[:last_line + 1]) + "\n")
            loaded = client.call("model", path=prefix)
            if not loaded.get("ok", False):
                return CliCommandResult(payload={"ok": False, "error": loaded.get("error", loaded), "pass": row},
                                        exit_code=3)
            capture = os.path.join(out_dir, f"pass{number}_{stage}.png")
            client.call("multiview", path=capture, point=center, radius=radius, shading=shading)
            label = f"pass {number} ({node}) strokes on its input" if stage == "in" else f"pass {number} ({node}) result"
            if stage == "out" and reference:
                compared = client.call("mesh-compare", reference=os.path.abspath(reference), normalize=True)
                row["chamfer"] = compared.get("chamfer_distance")
                row["hausdorff"] = compared.get("hausdorff_distance")
                label += f"  chamfer {row['chamfer']:.4f}  hausdorff {row['hausdorff']:.4f}"
            frame = os.path.join(out_dir, f"frame{len(frames):03d}.png")
            _labelled(ffmpeg, capture, overlay if stage == "in" else "", label, frame, width)
            frames.append(frame)
        rows.append(row)

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
    return CliCommandResult(payload={"ok": True, "passes": rows, "frames": len(frames), "mp4": video, "gif": gif})
