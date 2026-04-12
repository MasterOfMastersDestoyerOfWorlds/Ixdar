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
                      wireframe: bool = False, ortho: bool = False) -> dict:
    """Capture 8 views and composite into a 4x2 grid PNG."""
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
    view_dist = max(mesh_radius * 2.5, 1.0)

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

    composite.save(out_path)

    # Clean up individual frames
    for i in range(len(VIEWS)):
        frame_path = os.path.join(tmp_dir, f"view_{i}.png")
        if os.path.exists(frame_path):
            os.remove(frame_path)

    return {
        "path": out_path,
        "width": 4 * cell_w,
        "height": 2 * cell_h,
        "views": len(captures),
    }


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Capture 8-view multiview composite")
    parser.add_argument("output", nargs="?", default="/tmp/multiview.png", help="Output PNG path")
    parser.add_argument("--wireframe", action="store_true", help="Capture in wireframe mode")
    parser.add_argument("--ortho", action="store_true", help="Use orthographic projection")
    args = parser.parse_args()
    client = AutomationClient()
    flags = []
    if args.wireframe:
        flags.append("wireframe")
    if args.ortho:
        flags.append("ortho")
    mode = f" ({', '.join(flags)})" if flags else ""
    print(f"Capturing 8 views{mode}...")
    result = capture_multiview(client, args.output, wireframe=args.wireframe, ortho=args.ortho)
    print(f"Saved: {result}")
