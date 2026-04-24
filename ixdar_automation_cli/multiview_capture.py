#!/usr/bin/env python3
"""Capture 8-view composite of the mesh viewer by orchestrating orbit + screenshot calls."""

import math
import os
import sys
import time

from automation_client import AutomationClient

VIEWS = [
    (math.pi / 2, 0, "Front"),
    (0, 0, "Right"),
    (-math.pi / 2, 0, "Back"),
    (math.pi, 0, "Left"),
    (math.pi / 2, 1.45, "Top"),
    (math.pi / 2, -1.45, "Bottom"),
    (math.pi / 4, 0.4, "3/4 Front-R"),
    (3 * math.pi / 4, 0.4, "3/4 Front-L"),
]


def capture_multiview(client: AutomationClient, out_path: str = "/tmp/multiview.png",
                      wireframe: bool = False, ortho: bool = False,
                      max_side: int = 1920, zoom: float | str = 1.0) -> dict:
    """Capture 8 views and composite into a 4x2 grid PNG.

    Composite is downscaled so its long side fits under ``max_side`` (default
    1920, which stays under Claude's ~2000px image limit). Per-view PNGs are
    kept next to the composite at native cell resolution so individual views
    (e.g. Right for checking teeth) can be read without grid compression.
    Pass ``max_side=0`` to skip the downscale.

    ``zoom`` controls camera distance relative to the current 2.5× margin.
    ``1.0`` (default) keeps the historical framing. ``2.0`` = twice as close
    (mouth fills the frame instead of being a 30px strip). ``"fit"`` packs
    the mesh against the frame edges with a 2% safety margin using the
    45° FOV.
    """
    from PIL import Image, ImageDraw, ImageFont
    import base64
    import io

    saved_ortho = False
    if ortho:
        proj = client.get_projection()
        saved_ortho = proj.get("orthographic", False)
        if not saved_ortho:
            client.set_projection(orthographic=True)

    if wireframe:
        client.toggle_wireframe()
        time.sleep(0.1)

    # Get current orbit to restore later
    orbit = client.get_orbit()
    saved_az = orbit.get("azimuth", 0.785)
    saved_el = orbit.get("elevation", 0.419)
    saved_dist = orbit.get("distance", 3.5)
    mesh_radius = orbit.get("mesh_radius", 1.0)
    # Distance factor mirrors AutomationRuntime.parseZoomPerspective so the
    # Python CLI and Java multiview endpoint produce identical framing. 45°
    # FOV; "fit" = 1.02/sin(22.5°) ≈ 2.67; numeric zoom divides 2.5× default.
    import math
    if isinstance(zoom, str) and zoom.lower() == "fit":
        dist_factor = 1.02 / math.sin(math.radians(22.5))
    elif isinstance(zoom, (int, float)) and zoom > 0:
        dist_factor = 2.5 / float(zoom)
    else:
        dist_factor = 2.5
    view_dist = max(mesh_radius * dist_factor, 1.0)

    captures: list[tuple[Image.Image, str]] = []
    tmp_dir = "/tmp/multiview_frames"
    os.makedirs(tmp_dir, exist_ok=True)

    for i, (az, el, label) in enumerate(VIEWS):
        # Set orbit and wait for render
        client.set_orbit(az, el, view_dist)
        # Capture screenshot
        frame_path = os.path.join(tmp_dir, f"view_{i}.png")
        result = client.screenshot(out_path=frame_path)
        img = Image.open(frame_path)
        captures.append((img, label))

    # Restore original orbit
    client.set_orbit(saved_az, saved_el, saved_dist)

    if wireframe:
        client.toggle_wireframe()

    if ortho and not saved_ortho:
        client.set_projection(orthographic=False)

    if not captures:
        return {"error": "No captures taken"}

    cell_w, cell_h = captures[0][0].size
    composite = Image.new("RGB", (4 * cell_w, 2 * cell_h), (18, 18, 18))
    draw = ImageDraw.Draw(composite)

    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", max(14, cell_h // 20))
    except (OSError, IOError):
        font = ImageFont.load_default()

    for i, (img, label) in enumerate(captures):
        col = i % 4
        row = i // 4
        dx, dy = col * cell_w, row * cell_h
        composite.paste(img, (dx, dy))
        # Label with shadow
        draw.text((dx + 9, dy + 5), label, fill=(0, 0, 0), font=font)
        draw.text((dx + 8, dy + 4), label, fill=(255, 255, 255), font=font)

    comp_w, comp_h = composite.size
    if max_side and max(comp_w, comp_h) > max_side:
        scale = max_side / max(comp_w, comp_h)
        composite = composite.resize(
            (round(comp_w * scale), round(comp_h * scale)),
            Image.Resampling.LANCZOS,
        )
    composite.save(out_path)

    out_dir = os.path.dirname(os.path.abspath(out_path))
    stem, _ = os.path.splitext(os.path.basename(out_path))
    per_view: list[dict] = []
    import re
    for i, (img, label) in enumerate(captures):
        slug = re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_")
        view_out = os.path.join(out_dir, f"{stem}-{slug}.png")
        img.save(view_out)
        per_view.append({"label": label, "path": view_out,
                         "width": img.size[0], "height": img.size[1]})

    # Clean up individual raw frame captures (the labeled per-view PNGs
    # written above supersede them).
    for i in range(len(VIEWS)):
        frame_path = os.path.join(tmp_dir, f"view_{i}.png")
        if os.path.exists(frame_path):
            os.remove(frame_path)

    return {
        "path": out_path,
        "width": composite.size[0],
        "height": composite.size[1],
        "views": len(captures),
        "per_view": per_view,
    }


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Capture 8-view multiview composite")
    parser.add_argument("output", nargs="?", default="/tmp/multiview.png", help="Output PNG path")
    parser.add_argument("--wireframe", action="store_true", help="Capture in wireframe mode")
    parser.add_argument("--ortho", action="store_true", help="Use orthographic projection")
    parser.add_argument("--max-side", type=int, default=1920,
                        help="Downscale composite so its long side fits under this many pixels "
                             "(default 1920, under Claude's ~2000px limit). Pass 0 to skip.")
    parser.add_argument("--zoom", default="1.0",
                        help="Camera distance multiplier. 1.0 = default 2.5x-radius framing; "
                             "2.0 = twice as close (mouth fills frame); 'fit' = pack mesh "
                             "against frame edges with 2% safety margin.")
    args = parser.parse_args()
    try:
        zoom_arg: float | str = float(args.zoom)
    except ValueError:
        zoom_arg = args.zoom  # "fit" or similar
    client = AutomationClient()
    flags = []
    if args.wireframe:
        flags.append("wireframe")
    if args.ortho:
        flags.append("ortho")
    mode = f" ({', '.join(flags)})" if flags else ""
    print(f"Capturing 8 views{mode} zoom={zoom_arg}...")
    result = capture_multiview(client, args.output, wireframe=args.wireframe,
                               ortho=args.ortho, max_side=args.max_side, zoom=zoom_arg)
    print(f"Saved: {result}")
