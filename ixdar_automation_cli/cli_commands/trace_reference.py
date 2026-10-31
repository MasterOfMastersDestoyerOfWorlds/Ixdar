"""Trace a reference picture's silhouette into one cell of a multiview drawing template.

The stroke-sculpt sculptor compares its sculpt with reference pictures whose camera it does not
know. This command lifts the subject's outline out of a picture (foreground against the picture's
border colour, largest blob, its outer contour simplified) and writes it into a cell of a
``multiview --svg`` template as silhouette polylines the ``stroke_sculpt`` node lifts. The outline
is placed from pixels alone: scaled, aspect kept, so its height matches the current sculpt's
silhouette in that cell of the capture the template was written with, and centred on it. Interior
edges (Canny inside the subject) can be written to a second SVG for viewing.

OpenCV (Apache 2.0) does the image work.

Usage:
    uv run ixdar-cli trace-reference --reference tmp/references/ref_04_right.png \
        --template tmp/shape.svg --cell Right
"""

import os
import re

import cv2
import numpy

from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command

ROOT_TAG = re.compile(r"<svg\b[^>]*>")
CELL_LINE = re.compile(r"cell (\d+) \(column \d+, row \d+, x (\d+)-(\d+), y (\d+)-(\d+)\): ([^\n]+)")
DRAWN_OVER = re.compile(r"Drawn over (\S+?)\.\s")
CAPTURE_COLOUR_TOLERANCE = 24
EDGE_MINIMUM_PIXELS = 25


def _subject_mask(image: numpy.ndarray, background: numpy.ndarray, tolerance: int) -> numpy.ndarray:
    """Mask of the largest blob standing out from a background colour, its holes filled.

    :param image: BGR picture
    :param background: BGR colour of the background
    :param tolerance: largest summed channel difference still counted as background
    :return: uint8 mask, 255 on the subject, all zero when nothing stands out
    """
    difference = numpy.abs(image.astype(numpy.int32) - background.astype(numpy.int32)).sum(axis=2)
    mask = (difference > tolerance).astype(numpy.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, numpy.ones((5, 5), numpy.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    filled = numpy.zeros_like(mask)
    if contours:
        cv2.drawContours(filled, [max(contours, key=cv2.contourArea)], -1, 255, cv2.FILLED)
    return filled


@cli_command(name="trace-reference")
def trace_reference(
    reference: str,
    template: str,
    cell: str,
    capture: str = "",
    out: str = "",
    edges: str = "",
    strength: float = 1.0,
    stroke_width: float = 60.0,
    pieces: int = 4,
    simplify: float = 2.0,
    tolerance: int = 40,
) -> CliCommandResult:
    """Write a reference picture's silhouette into a template cell as silhouette polylines, fitted to the sculpt.

    The subject is whatever stands out from the picture's border colour. Its outline is scaled
    (aspect kept) to the height of the sculpt's silhouette in that cell of the capture and centred
    on it; no camera of the reference is read, so a picture taken at another angle, lens or roll
    is placed by its outline's bounding box only. The outline is split into ``--pieces`` polylines
    so parts of it can be deleted or moved; each stays inside the cell.

    :param reference: Reference picture (PNG or JPEG) of the subject.
    :param template: SVG drawing template written by multiview --svg.
    :param cell: Cell to trace into, by its view name (Front, Right, 3/4 Front-R, ...) or index.
    :param capture: Multiview PNG the template was written with; default the PNG its comment names, next to it.
    :param out: SVG written; default the template itself, the polylines added before its end.
    :param edges: Also write the subject's interior edges, placed the same way, to this SVG for viewing (it records no camera, so no node lifts it).
    :param strength: data-strength of the silhouette polylines.
    :param stroke_width: stroke-width of the silhouette polylines, in template units.
    :param pieces: Number of polylines the closed outline is split into.
    :param simplify: Largest distance, in template units, a dropped outline point may lie from the kept polyline.
    :param tolerance: Largest summed RGB difference from the picture's border colour still counted as background.
    """
    root = str(checkout_root())
    template_path = template if os.path.isabs(template) else os.path.join(root, template)
    text = open(template_path, encoding="utf-8").read()
    root_tag = ROOT_TAG.search(text).group()
    attributes = dict(re.findall(r"([\w-]+)\s*=\s*\"([^\"]*)\"", root_tag))
    if "data-center" not in attributes:
        return CliCommandResult(payload={"ok": False, "error": f"{template} records no camera; write it with "
                                         "ixdar-cli multiview --svg"}, exit_code=2)
    cells = {int(index): (int(left), int(right), int(top), int(bottom), label.strip())
             for index, left, right, top, bottom, label in CELL_LINE.findall(text)}
    chosen = next((index for index, rect in cells.items()
                   if rect[4].lower() == cell.lower() or str(index) == cell), None)
    if chosen is None:
        return CliCommandResult(payload={"ok": False, "error": f"no cell {cell} in {template}",
                                         "cells": [rect[4] for rect in cells.values()]}, exit_code=2)
    left, right, top, bottom, label = cells[chosen]
    view_box = [float(value) for value in attributes.get("viewBox", "").replace(",", " ").split()]
    composite_width = int(attributes["data-columns"]) * int(attributes["data-cell-width"])
    units_per_pixel = view_box[2] / composite_width if len(view_box) == 4 else 1.0

    if not capture:
        named = DRAWN_OVER.search(text)
        capture = os.path.join(os.path.dirname(template_path), named.group(1)) if named else ""
    capture_path = capture if os.path.isabs(capture) or not capture else os.path.join(root, capture)
    if not capture_path or not os.path.isfile(capture_path):
        return CliCommandResult(payload={"ok": False, "error": f"no capture at {capture_path or '?'}; pass --capture, "
                                         "the multiview PNG the template was written with"}, exit_code=2)
    sculpt_cell = cv2.imread(capture_path, cv2.IMREAD_COLOR)[top:bottom, left:right]
    sculpt_mask = _subject_mask(sculpt_cell, sculpt_cell[0, 0], CAPTURE_COLOUR_TOLERANCE)
    picture = cv2.imread(reference if os.path.isabs(reference) else os.path.join(root, reference), cv2.IMREAD_COLOR)
    if picture is None:
        return CliCommandResult(payload={"ok": False, "error": f"cannot read {reference}"}, exit_code=2)
    border = numpy.concatenate([picture[0], picture[-1], picture[:, 0], picture[:, -1]])
    reference_mask = _subject_mask(picture, numpy.median(border, axis=0), tolerance)
    if not sculpt_mask.any() or not reference_mask.any():
        return CliCommandResult(payload={"ok": False, "error": "no subject stands out from the background in the "
                                         + ("capture's cell" if not sculpt_mask.any() else "reference")}, exit_code=3)

    # Fit: uniform scale matching heights, bounding-box centres coincide, in composite pixels.
    sculpt_x, sculpt_y, sculpt_w, sculpt_h = cv2.boundingRect(sculpt_mask)
    reference_x, reference_y, reference_w, reference_h = cv2.boundingRect(reference_mask)
    scale = sculpt_h / reference_h
    offset = numpy.array([left + sculpt_x + sculpt_w / 2 - scale * (reference_x + reference_w / 2),
                          top + sculpt_y + sculpt_h / 2 - scale * (reference_y + reference_h / 2)])
    low = numpy.array([left, top], dtype=float)
    high = numpy.array([right - 1, bottom - 1], dtype=float)

    contours, _ = cv2.findContours(reference_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    outline = numpy.clip(contours[0].reshape(-1, 2) * scale + offset, low, high) * units_per_pixel
    outline = cv2.approxPolyDP(outline.astype(numpy.float32).reshape(-1, 1, 2), simplify, True).reshape(-1, 2)
    closed = numpy.vstack([outline, outline[:1]])
    lengths = numpy.concatenate([[0.0], numpy.cumsum(numpy.hypot(*numpy.diff(closed, axis=0).T))])
    piece_count = max(1, min(pieces, len(outline)))
    cuts = [int(numpy.searchsorted(lengths, lengths[-1] * piece / piece_count)) for piece in range(piece_count)]
    cuts.append(len(closed) - 1)
    polylines = [closed[start:end + 1] for start, end in zip(cuts, cuts[1:]) if end > start]

    def points(polyline: numpy.ndarray) -> str:
        return " ".join(f"{x:.1f},{y:.1f}" for x, y in polyline)

    shapes = "".join(f'<polyline data-kind="silhouette" data-strength="{strength:g}" stroke-width="{stroke_width:g}" '
                     f'fill="none" stroke="blue" points="{points(polyline)}"/>\n' for polyline in polylines)
    note = (f"<!-- trace-reference: {os.path.basename(reference)} into cell {chosen} ({label}), scale {scale:.4f}, "
            f"fitted to the sculpt's silhouette height {sculpt_h} px -->\n")
    end = text.rindex("</svg>")
    out_path = out or template_path
    out_path = out_path if os.path.isabs(out_path) else os.path.join(root, out_path)
    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write(text[:end] + note + shapes + text[end:])

    payload = {"ok": True, "svg": out_path, "cell": chosen, "view": label, "capture": capture_path,
               "polylines": len(polylines), "points": int(sum(len(polyline) for polyline in polylines)),
               "scale": round(scale, 5),
               "sculptBox": [left + sculpt_x, top + sculpt_y, left + sculpt_x + sculpt_w, top + sculpt_y + sculpt_h],
               "referenceBox": [reference_x, reference_y, reference_x + reference_w, reference_y + reference_h]}
    if edges:
        gray = cv2.GaussianBlur(cv2.cvtColor(picture, cv2.COLOR_BGR2GRAY), (5, 5), 0)
        inside = cv2.erode(reference_mask, numpy.ones((7, 7), numpy.uint8))
        edge_pixels = cv2.Canny(gray, 30, 90) & inside
        found, _ = cv2.findContours(edge_pixels, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
        lines = [numpy.clip(contour.reshape(-1, 2) * scale + offset, low, high) * units_per_pixel
                 for contour in found if len(contour) >= EDGE_MINIMUM_PIXELS]
        lines = [cv2.approxPolyDP(line.astype(numpy.float32).reshape(-1, 1, 2), simplify, False).reshape(-1, 2)
                 for line in lines]
        edges_path = edges if os.path.isabs(edges) else os.path.join(root, edges)
        with open(edges_path, "w", encoding="utf-8") as handle:
            handle.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{attributes.get("width", "")}" '
                         f'height="{attributes.get("height", "")}" viewBox="{attributes.get("viewBox", "")}">\n'
                         f"<!-- interior edges of {os.path.basename(reference)}, placed as in {out_path} -->\n"
                         + "".join(f'<polyline fill="none" stroke="#e08a1e" stroke-width="2" points="{points(line)}"/>\n'
                                   for line in lines) + "</svg>\n")
        payload["edges"] = edges_path
        payload["edgeLines"] = len(lines)
    return CliCommandResult(payload=payload)
