"""Trace several reference pictures into one orthographic outlines template at one shared scale.

``trace-reference`` places one picture into one cell by fitting it to a sculpt already captured in
that cell, so every view gets its own scale and a capture must exist first. A silhouette_hull needs
the opposite: every outline at the same world scale, before any mesh exists. This command takes
several ``--reference picture=cell`` pairs and writes all their outlines into one template whose
root records the cameras (the ``multiview --svg`` form), writing that template too when there is
none, so no scene has to run. The cells and their angles come from the given template's
``data-views``, or else from the ``multiviewCells`` the build writes into automation_routes.json from
MultiviewScreenshot.

Shared scale and centre: each picture's subject (as trace-reference finds it) has a bounding box in
picture pixels and an unknown world size per picture pixel. In a view along a world axis the box's
width and height measure the two other axes; every axis is measured by the views across it (height
by front, back, right and left; width by front, back, top and bottom; depth by right, left, top and
bottom). The per-picture scales are fitted by least squares in log space so those measurements agree,
with the subject's height pinned to ``--height`` world units. An oblique view (the 3/4 cells) measures
no single axis; its scale is the one that makes its box match an ellipsoid with the fitted extents
seen from its camera. Every outline is centred on its cell, the box centre on the projection of the
origin.

OpenCV (Apache 2.0) does the image work.

Usage:
    uv run ixdar-cli trace-outlines --out tmp/skull/outlines.svg \
        --reference tmp/skull/references/ref_01_front.png=Front \
        --reference tmp/skull/references/ref_04_right.png=Right \
        --reference tmp/skull/references/ref_07_top.png=Top
"""

import math
import os
import re

import cv2
import numpy

from ..automation_client import load_manifest
from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command
from .trace_reference import subject_mask

AXIS_ALIGNED = 0.99
HULL_LATTICE = 96
ROOT_TAG = re.compile(r"<svg\b[^>]*>")


def multiview_cells() -> tuple[list[str], list[tuple[float, float]], int]:
    """The multiview grid's cells as the Java side defines them, read from the routes manifest the
    build regenerates from MultiviewScreenshot (``multiviewCells``), so no table is copied here.

    :return: (labels, (azimuth, elevation) radians per cell, columns)
    """
    manifest = load_manifest()
    cells = manifest.get("multiviewCells", [])
    return ([cell["label"] for cell in cells], [(cell["azimuth"], cell["elevation"]) for cell in cells],
            int(manifest.get("multiviewColumns", 4)))


def _camera_axes(azimuth: float, elevation: float) -> tuple[numpy.ndarray, numpy.ndarray, numpy.ndarray]:
    """A cell camera's picture axes and view direction in world space, as Camera3D's orbit sets them.

    :param azimuth: orbit azimuth in radians
    :param elevation: orbit elevation in radians
    :return: (right, up, forward) unit vectors
    """
    toward_eye = numpy.array([math.cos(elevation) * math.cos(azimuth), math.sin(elevation),
                              math.cos(elevation) * math.sin(azimuth)])
    up = numpy.array([-math.sin(elevation) * math.cos(azimuth), math.cos(elevation),
                      -math.sin(elevation) * math.sin(azimuth)])
    forward = -toward_eye
    right = numpy.cross(forward, up)
    return right / numpy.linalg.norm(right), up, forward


@cli_command(name="trace-outlines")
def trace_outlines(
    reference: list[str],
    out: str,
    template: str = "",
    height: float = 2.0,
    fill: float = 0.45,
    cell_size: int = 1280,
    simplify: float = 1.0,
    tolerance: int = 40,
) -> CliCommandResult:
    """Write several reference pictures' outlines into one orthographic outlines template at one shared scale.

    Each picture's subject is found as trace-reference finds it. The pictures' scales are fitted so
    the axis each pair of views shares agrees (height from front/back/side, width from front/top,
    depth from side/top), the subject's height being ``height`` world units, and each outline is
    centred on its cell. No capture or running scene is needed: without ``template`` the template
    itself is written, orthographic, framed so the subject's height fills ``fill`` of a cell.

    :param reference: Picture and cell as picture=cell, cell by name (Front, Right, 3/4 Front-R, ...) or index; repeat per view.
    :param out: SVG written: the template's root and cell list, then one silhouette polyline per picture.
    :param template: Existing outlines template whose root (cameras, cell size) to keep; default a new orthographic one.
    :param height: World height the subject's outlines are scaled to.
    :param fill: Share of a new template's cell height the subject's height fills.
    :param cell_size: Cell width and height in pixels of a new template.
    :param simplify: Largest distance, in template units, a dropped outline point may lie from the kept polyline.
    :param tolerance: Largest summed RGB difference from a picture's border colour still counted as background.
    """
    root = str(checkout_root())
    labels, angles, columns = multiview_cells()
    if not angles:
        return CliCommandResult(payload={"ok": False, "error": "the routes manifest lists no multiviewCells; run the "
                                         "Maven build, which regenerates automation_routes.json"}, exit_code=2)
    if template:
        template_path = template if os.path.isabs(template) else os.path.join(root, template)
        text = open(template_path, encoding="utf-8").read()
        root_tag = ROOT_TAG.search(text).group()
        attributes = dict(re.findall(r"([\w-]+)\s*=\s*\"([^\"]*)\"", root_tag))
        cell_width, cell_height = int(attributes["data-cell-width"]), int(attributes["data-cell-height"])
        columns = int(attributes["data-columns"])
        angles = [tuple(float(value) for value in view.split()[:2]) for view in attributes["data-views"].split(";")]
        distance, field_of_view = float(attributes["data-distance"]), float(attributes["data-fov"])
        view_box = [float(value) for value in attributes.get("viewBox", "").replace(",", " ").split()]
        units_per_pixel = view_box[2] / (columns * cell_width) if len(view_box) == 4 else 1.0
        if attributes.get("data-projection") != "orthographic":
            return CliCommandResult(payload={"ok": False, "error": f"{template} is not orthographic; outlines at one "
                                             "shared scale need an orthographic template"}, exit_code=2)
    else:
        cell_width = cell_height = cell_size
        field_of_view, units_per_pixel = 45.0, 1.0
        distance = height / (fill * 2.0 * math.tan(math.radians(field_of_view) / 2.0))
    world_per_cell_pixel = 2.0 * distance * math.tan(math.radians(field_of_view) / 2.0) / cell_height

    # Each picture: its cell, its subject's outline and box, and which world axes its box measures.
    views = []
    for pair in reference:
        picture, _, cell_name = pair.rpartition("=")
        cell = next((index for index, label in enumerate(labels)
                     if label.lower() == cell_name.lower() or str(index) == cell_name), None)
        if not picture or cell is None or cell >= len(angles):
            return CliCommandResult(payload={"ok": False, "error": f"--reference {pair} is not picture=cell",
                                             "cells": labels[:len(angles)]}, exit_code=2)
        if any(view["cell"] == cell for view in views):
            return CliCommandResult(payload={"ok": False, "error": f"two pictures for cell {labels[cell]}"},
                                    exit_code=2)
        image = cv2.imread(picture if os.path.isabs(picture) else os.path.join(root, picture), cv2.IMREAD_COLOR)
        if image is None:
            return CliCommandResult(payload={"ok": False, "error": f"cannot read {picture}"}, exit_code=2)
        border = numpy.concatenate([image[0], image[-1], image[:, 0], image[:, -1]])
        mask = subject_mask(image, numpy.median(border, axis=0), tolerance)
        if not mask.any():
            return CliCommandResult(payload={"ok": False, "error": f"no subject stands out in {picture}"}, exit_code=3)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        x, y, width, box_height = cv2.boundingRect(mask)
        right, up, forward = _camera_axes(*angles[cell])
        axis = int(numpy.argmax(numpy.abs(forward)))
        aligned = abs(forward[axis]) >= AXIS_ALIGNED
        views.append({"picture": picture, "cell": cell, "outline": contours[0].reshape(-1, 2).astype(float),
                      "centre": (x + width / 2.0, y + box_height / 2.0), "size": (float(width), float(box_height)),
                      "axes": (int(numpy.argmax(numpy.abs(right))), int(numpy.argmax(numpy.abs(up))))
                      if aligned else None, "right": right, "up": up})

    # Least squares in log space: log scale_i + log size_i,k agree across the views measuring axis k,
    # and the views measuring height agree with log height.
    aligned_views = [index for index, view in enumerate(views) if view["axes"] is not None]
    if not any(1 in views[index]["axes"] for index in aligned_views):
        return CliCommandResult(payload={"ok": False, "error": "no front, back, right or left picture measures the "
                                         "subject's height"}, exit_code=2)
    rows, targets = [], []
    for axis in range(3):
        measuring = [(index, views[index]["size"][views[index]["axes"].index(axis)])
                     for index in aligned_views if axis in views[index]["axes"]]
        for (first, first_size), (second, second_size) in zip(measuring, measuring[1:]):
            row = numpy.zeros(len(views))
            row[first], row[second] = 1.0, -1.0
            rows.append(row)
            targets.append(math.log(second_size) - math.log(first_size))
        if axis == 1:
            for index, size in measuring:
                row = numpy.zeros(len(views))
                row[index] = 1.0
                rows.append(row)
                targets.append(math.log(height) - math.log(size))
    solution = numpy.linalg.lstsq(numpy.array(rows), numpy.array(targets), rcond=None)[0]
    scales = numpy.exp(solution)
    extents = numpy.zeros(3)
    for axis in range(3):
        measured = [scales[index] * views[index]["size"][views[index]["axes"].index(axis)]
                    for index in aligned_views if axis in views[index]["axes"]]
        extents[axis] = numpy.mean(measured) if measured else height
    # Placement in cell pixels: an axis view's box centre on the cell centre, the origin's projection.
    # An oblique view measures no single axis and the subject's box centre need not project to the
    # origin, so it is fitted to the silhouette, in its cell, of the visual hull the axis views carve
    # on a coarse lattice: scale from the mean of the width and height ratios, centre on its box.
    centres, pixels_per_picture_pixel = {}, {}
    masks = {}
    for index in aligned_views:
        view = views[index]
        pixels_per_picture_pixel[index] = scales[index] / world_per_cell_pixel
        centres[index] = numpy.array([cell_width / 2.0 - 0.5, cell_height / 2.0 - 0.5])
        mask = numpy.zeros((cell_height, cell_width), numpy.uint8)
        placed_outline = (view["outline"] - numpy.array(view["centre"])) * pixels_per_picture_pixel[index] + centres[index]
        cv2.fillPoly(mask, [numpy.round(placed_outline).astype(numpy.int32)], 255)
        masks[index] = mask > 0
    steps = numpy.linspace(-0.6, 0.6, HULL_LATTICE)
    lattice = numpy.stack(numpy.meshgrid(steps * extents[0], steps * extents[1], steps * extents[2],
                                         indexing="ij"), axis=-1).reshape(-1, 3)
    kept = numpy.ones(len(lattice), bool)
    for index in aligned_views:
        view = views[index]
        columns_at = numpy.floor(lattice @ view["right"] / world_per_cell_pixel + cell_width / 2.0).astype(int)
        rows_at = numpy.floor(cell_height / 2.0 - lattice @ view["up"] / world_per_cell_pixel).astype(int)
        inside = (columns_at >= 0) & (columns_at < cell_width) & (rows_at >= 0) & (rows_at < cell_height)
        kept &= inside & masks[index][numpy.clip(rows_at, 0, cell_height - 1), numpy.clip(columns_at, 0, cell_width - 1)]
    for index, view in enumerate(views):
        if view["axes"] is not None:
            continue
        if not kept.any():
            return CliCommandResult(payload={"ok": False, "error": "the axis views carve no common volume to fit "
                                             f"{view['picture']} to"}, exit_code=3)
        across = lattice[kept] @ view["right"] / world_per_cell_pixel + cell_width / 2.0 - 0.5
        down = cell_height / 2.0 - 0.5 - lattice[kept] @ view["up"] / world_per_cell_pixel
        hull_size = (across.max() - across.min(), down.max() - down.min())
        pixels_per_picture_pixel[index] = (hull_size[0] / view["size"][0] + hull_size[1] / view["size"][1]) / 2.0
        centres[index] = numpy.array([(across.max() + across.min()) / 2.0, (down.max() + down.min()) / 2.0])
        scales[index] = pixels_per_picture_pixel[index] * world_per_cell_pixel

    # Write: the template's root and cell list, then one silhouette polyline per picture.
    width_total, height_total = columns * cell_width, ((len(angles) + columns - 1) // columns) * cell_height
    if template:
        end = text.rindex("</svg>")
        head = re.sub(r"<polyline\b[^>]*/>\n?|<!-- trace-(reference|outlines):.*?-->\n?", "", text[:end])
    else:
        layout = "".join(f"\n  cell {cell} (column {cell % columns}, row {cell // columns}, x "
                         f"{cell % columns * cell_width}-{(cell % columns + 1) * cell_width}, y "
                         f"{cell // columns * cell_height}-{(cell // columns + 1) * cell_height}): {label}"
                         for cell, label in enumerate(labels))
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width_total}" height="{height_total}" '
                f'viewBox="0 0 {width_total} {height_total}"\n'
                f'     data-center="0.000000 0.000000 0.000000" data-distance="{distance:.6f}" '
                f'data-fov="{field_of_view:.4f}" data-projection="orthographic"\n'
                f'     data-cell-width="{cell_width}" data-cell-height="{cell_height}" data-columns="{columns}"\n'
                f'     data-views="{";".join(f"{azimuth:.6f} {elevation:.6f}" for azimuth, elevation in angles)}">\n'
                f"<!-- Outlines written by ixdar-cli trace-outlines at one shared scale, subject height {height:g}. "
                f"Cells:{layout}\n-->\n")
    shapes, placed = "", []
    for index, view in enumerate(views):
        cell = view["cell"]
        left, top = cell % columns * cell_width, cell // columns * cell_height
        outline = ((view["outline"] - numpy.array(view["centre"])) * pixels_per_picture_pixel[index]
                   + centres[index] + [left, top])
        outline = numpy.clip(outline, [left, top], [left + cell_width - 1, top + cell_height - 1]) * units_per_pixel
        outline = cv2.approxPolyDP(outline.astype(numpy.float32).reshape(-1, 1, 2), simplify, True).reshape(-1, 2)
        closed = numpy.vstack([outline, outline[:1]])
        shapes += (f"<!-- trace-outlines: {os.path.basename(view['picture'])} into cell {cell} ({labels[cell]}), "
                   f"{scales[index]:.6f} world units per picture pixel -->\n"
                   f'<polyline data-kind="silhouette" fill="none" stroke="blue" points="'
                   + " ".join(f"{x:.1f},{y:.1f}" for x, y in closed) + '"/>\n')
        placed.append({"picture": view["picture"], "cell": cell, "view": labels[cell],
                       "worldPerPicturePixel": round(float(scales[index]), 6), "points": len(outline),
                       "aligned": view["axes"] is not None})
    out_path = out if os.path.isabs(out) else os.path.join(root, out)
    if os.path.dirname(out_path):
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write(head + shapes + "</svg>\n")
    return CliCommandResult(payload={"ok": True, "svg": out_path, "outlines": placed,
                                     "extents": [round(float(value), 4) for value in extents],
                                     "distance": round(distance, 6)})
