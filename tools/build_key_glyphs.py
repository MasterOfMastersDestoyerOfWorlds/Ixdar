"""Build the key and mouse glyph atlas the control hints draw: res/keyglyphs.png and .json.

The glyphs are drawn here as outlines in the text font's em, written to a small OpenType font
and handed to msdf-atlas-gen with the settings opensans.json was built with, so Font draws them
at the text's scale from a second MSDF texture. Each keyboard key is a keycap: a rounded ring with
its label inside, the label an OpenSans Condensed SemiBold character or word, or for enter,
shift, backspace and the arrows a Material Design Icons symbol. The mouse glyphs are drawn here.

Material Design Icons, https://github.com/Templarian/MaterialDesign, Apache License 2.0: the
symbols are read from the copy the JetBrainsMono Nerd Font carries
(https://github.com/ryanoasis/nerd-fonts, MIT), by code point.

Run from the Ixdar checkout:

    uv run --with fonttools --with skia-pathops python tools/build_key_glyphs.py \
        --atlas-gen ~/Code/msdf-atlas-gen/build/bin/msdf-atlas-gen

The code points must match ixdar.graphics.render.text.KeyGlyphs.
"""

import argparse
import json
import subprocess
from pathlib import Path

import pathops
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

RES = Path("ixdar-app/src/main/resources/res")
TEXT_FONT = RES / "OpenSans_Condensed-SemiBold.ttf"
ICON_FONT = Path("/usr/share/fonts/TTF/JetBrainsMonoNerdFont-Regular.ttf")
UPM = 2048

# Code points; KeyGlyphs mirrors these.
NAMED_START = 0xE100
NAMED = [
    "enter",
    "shift",
    "ctrl",
    "esc",
    "backspace",
    "del",
    "end",
    "tab",
    "alt",
    "up",
    "down",
    "left",
    "right",
    "click",
    "drag",
    "scroll",
    "space",
    "home",
]
KEYCAP_START = 0xE200
KEYCAP_CHARS = [chr(c) for c in range(33, 127) if not ("a" <= chr(c) <= "z")]

# Material Design Icons code points in the Nerd Font.
MDI = {
    "keyboard_return": 0xF0311,
    "apple_keyboard_shift": 0xF0636,
    "backspace_outline": 0xF0B5C,
    "arrow_up": 0xF005D,
    "arrow_down": 0xF0045,
    "arrow_left": 0xF004D,
    "arrow_right": 0xF0054,
    "cursor_move": 0xF01BE,
}

# Geometry, in em.
KEY_BOTTOM = -0.16
KEY_TOP = 0.90
KEY_STROKE = 0.065
KEY_RADIUS = 0.20
KEY_PAD = 0.09
SIDE_BEARING = 0.05
CHAR_SCALE = 0.72
WORD_SCALE = 0.68
SYMBOL_SIZE = 0.56
CAP_HEIGHT = 0.7138671875
MOUSE_WIDTH = 0.60
MOUSE_BOTTOM = -0.14
MOUSE_TOP = 0.90
MOUSE_STROKE = 0.085
MOUSE_SPLIT = 0.50
DIVIDER = 0.07
KAPPA = 0.5523


def rounded_rect(left, bottom, right, top, radius):
    """A closed rounded rectangle, counter-clockwise, in em."""
    radius = min(radius, (right - left) / 2, (top - bottom) / 2)
    k = radius * KAPPA
    path = pathops.Path()
    pen = path.getPen()
    pen.moveTo((left + radius, bottom))
    pen.lineTo((right - radius, bottom))
    pen.curveTo(
        (right - radius + k, bottom), (right, bottom + radius - k), (right, bottom + radius)
    )
    pen.lineTo((right, top - radius))
    pen.curveTo((right, top - radius + k), (right - radius + k, top), (right - radius, top))
    pen.lineTo((left + radius, top))
    pen.curveTo((left + radius - k, top), (left, top - radius + k), (left, top - radius))
    pen.lineTo((left, bottom + radius))
    pen.curveTo((left, bottom + radius - k), (left + radius - k, bottom), (left + radius, bottom))
    pen.closePath()
    return path


def polygon(points):
    path = pathops.Path()
    pen = path.getPen()
    pen.moveTo(points[0])
    for point in points[1:]:
        pen.lineTo(point)
    pen.closePath()
    return path


def union(*paths):
    result = pathops.Path()
    for path in paths:
        result = pathops.op(result, path, pathops.PathOp.UNION)
    return result


def difference(a, b):
    return pathops.op(a, b, pathops.PathOp.DIFFERENCE)


def intersection(a, b):
    return pathops.op(a, b, pathops.PathOp.INTERSECTION)


def outline(font, codepoint, scale, dx, dy):
    """A glyph of `font` as a path, scaled from font units to em and moved."""
    glyph_set = font.getGlyphSet()
    name = font.getBestCmap()[codepoint]
    path = pathops.Path()
    glyph_set[name].draw(TransformPen(path.getPen(), (scale, 0, 0, scale, dx, dy)))
    return path


def fitted(font, codepoint, size, cx, cy):
    """A glyph scaled so its larger side is `size` em, centred on (cx, cy)."""
    raw = outline(font, codepoint, 1.0, 0, 0)
    left, bottom, right, top = raw.bounds
    scale = size / max(right - left, top - bottom)
    return outline(
        font,
        codepoint,
        scale,
        cx - (left + right) / 2 * scale,
        cy - (bottom + top) / 2 * scale,
    )


def text_path(text_font, text, scale):
    """`text` in the text font at `scale`, its pen starting at x 0, and its width in em."""
    units = text_font["head"].unitsPerEm
    cmap = text_font.getBestCmap()
    path = pathops.Path()
    pen_x = 0.0
    for ch in text:
        path = union(path, outline(text_font, ord(ch), scale / units, pen_x, 0))
        pen_x += text_font["hmtx"][cmap[ord(ch)]][0] * scale / units
    if path.bounds == (0, 0, 0, 0):
        return path, 0.0, 0.0
    left, _, right, _ = path.bounds
    return path, left, right


def keycap(inner_width):
    """A keycap ring wide enough for a label `inner_width` em wide, and its width."""
    height = KEY_TOP - KEY_BOTTOM
    width = max(height * 0.94, inner_width + 2 * KEY_PAD + 2 * KEY_STROKE)
    left = SIDE_BEARING
    right = left + width
    outer = rounded_rect(left, KEY_BOTTOM, right, KEY_TOP, KEY_RADIUS)
    inner = rounded_rect(
        left + KEY_STROKE,
        KEY_BOTTOM + KEY_STROKE,
        right - KEY_STROKE,
        KEY_TOP - KEY_STROKE,
        KEY_RADIUS - KEY_STROKE,
    )
    return difference(outer, inner), width


def text_keycap(text_font, text, scale):
    label, left, right = text_path(text_font, text, scale)
    ring, width = keycap(right - left)
    cx = SIDE_BEARING + width / 2
    cy = (KEY_BOTTOM + KEY_TOP) / 2
    dx = cx - (left + right) / 2
    dy = cy - CAP_HEIGHT * scale / 2
    if text in ",._":
        dy = KEY_BOTTOM + KEY_STROKE + 0.18
    moved = pathops.Path()
    label.draw(TransformPen(moved.getPen(), (1, 0, 0, 1, dx, dy)))
    return union(ring, moved), width + 2 * SIDE_BEARING


def symbol_keycap(icon_font, codepoint, size, wide=1.0):
    ring, width = keycap(size * wide)
    symbol = fitted(
        icon_font, codepoint, size, SIDE_BEARING + width / 2, (KEY_BOTTOM + KEY_TOP) / 2
    )
    return union(ring, symbol), width + 2 * SIDE_BEARING


def mouse(left, fill):
    """A mouse outline from `left`; `fill` is "left" for the left button, "wheel" for the wheel."""
    right = left + MOUSE_WIDTH
    cx = (left + right) / 2
    body = rounded_rect(left, MOUSE_BOTTOM, right, MOUSE_TOP, MOUSE_WIDTH / 2)
    inside = rounded_rect(
        left + MOUSE_STROKE,
        MOUSE_BOTTOM + MOUSE_STROKE,
        right - MOUSE_STROKE,
        MOUSE_TOP - MOUSE_STROKE,
        MOUSE_WIDTH / 2 - MOUSE_STROKE,
    )
    shape = difference(body, inside)
    split = polygon(
        [
            (left, MOUSE_SPLIT - DIVIDER / 2),
            (right, MOUSE_SPLIT - DIVIDER / 2),
            (right, MOUSE_SPLIT + DIVIDER / 2),
            (left, MOUSE_SPLIT + DIVIDER / 2),
        ]
    )
    shape = union(shape, intersection(split, body))
    if fill == "left":
        divider = polygon(
            [
                (cx - DIVIDER / 2, MOUSE_SPLIT),
                (cx + DIVIDER / 2, MOUSE_SPLIT),
                (cx + DIVIDER / 2, MOUSE_TOP),
                (cx - DIVIDER / 2, MOUSE_TOP),
            ]
        )
        button = polygon(
            [(left, MOUSE_SPLIT), (cx, MOUSE_SPLIT), (cx, MOUSE_TOP), (left, MOUSE_TOP)]
        )
        shape = union(shape, intersection(divider, body), intersection(button, body))
    else:
        wheel_half = 0.055
        wheel = rounded_rect(
            cx - wheel_half,
            MOUSE_SPLIT + 0.07,
            cx + wheel_half,
            MOUSE_TOP - 0.11,
            wheel_half,
        )
        shape = union(shape, wheel)
    return shape, right


def arrowheads(x):
    """Small up and down triangles at `x`, beside a scroll wheel."""
    half = 0.11
    up = polygon([(x - half, 0.62), (x + half, 0.62), (x, 0.84)])
    down = polygon([(x - half, 0.12), (x + half, 0.12), (x, -0.10)])
    return union(up, down)


def build_glyphs(text_font, icon_font):
    glyphs = {}
    word = lambda text: text_keycap(text_font, text, WORD_SCALE)
    glyphs["enter"] = symbol_keycap(icon_font, MDI["keyboard_return"], SYMBOL_SIZE)
    glyphs["shift"] = symbol_keycap(icon_font, MDI["apple_keyboard_shift"], SYMBOL_SIZE)
    glyphs["backspace"] = symbol_keycap(
        icon_font, MDI["backspace_outline"], SYMBOL_SIZE * 1.15, wide=1.3
    )
    for name in ("up", "down", "left", "right"):
        glyphs[name] = symbol_keycap(icon_font, MDI["arrow_" + name], SYMBOL_SIZE)
    for name in ("ctrl", "esc", "del", "end", "tab", "alt", "home"):
        glyphs[name] = word(name)
    glyphs["space"] = word("space")

    click, right = mouse(SIDE_BEARING, "left")
    glyphs["click"] = (click, right + SIDE_BEARING)
    drag, right = mouse(SIDE_BEARING, "left")
    move_size = 0.62
    move = fitted(icon_font, MDI["cursor_move"], move_size, right + 0.05 + move_size / 2, 0.30)
    glyphs["drag"] = (union(drag, move), right + 0.05 + move_size + SIDE_BEARING)
    scroll, right = mouse(SIDE_BEARING, "wheel")
    glyphs["scroll"] = (union(scroll, arrowheads(right + 0.15)), right + 0.30 + SIDE_BEARING)

    named = {NAMED_START + i: glyphs[name] for i, name in enumerate(NAMED)}
    keycaps = {
        KEYCAP_START + ord(ch): text_keycap(text_font, ch, CHAR_SCALE) for ch in KEYCAP_CHARS
    }
    return {**named, **keycaps}


def write_font(glyphs, out):
    order = [".notdef"] + ["uni%04X" % cp for cp in sorted(glyphs)]
    fb = FontBuilder(UPM, isTTF=False)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({cp: "uni%04X" % cp for cp in glyphs})
    charstrings = {}
    metrics = {}
    pen = T2CharStringPen(0, None)
    charstrings[".notdef"] = pen.getCharString()
    metrics[".notdef"] = (0, 0)
    for cp, (path, advance) in glyphs.items():
        name = "uni%04X" % cp
        width = round(advance * UPM)
        pen = T2CharStringPen(width, None)
        scaled = pathops.Path()
        path.draw(TransformPen(scaled.getPen(), (UPM, 0, 0, UPM, 0, 0)))
        scaled.draw(pen)
        charstrings[name] = pen.getCharString()
        left = round(scaled.bounds[0]) if scaled.bounds != (0, 0, 0, 0) else 0
        metrics[name] = (width, left)
    fb.setupCFF("IxdarKeyGlyphs", {"FullName": "Ixdar Key Glyphs"}, charstrings, {})
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=round(1.06884765625 * UPM), descent=round(-0.29296875 * UPM))
    fb.setupNameTable({"familyName": "Ixdar Key Glyphs", "styleName": "Regular"})
    fb.setupOS2(
        sTypoAscender=round(1.06884765625 * UPM),
        sTypoDescender=round(-0.29296875 * UPM),
        usWinAscent=round(1.06884765625 * UPM),
        usWinDescent=round(0.29296875 * UPM),
    )
    fb.setupPost()
    fb.save(str(out))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--atlas-gen", required=True, help="path to the msdf-atlas-gen binary")
    parser.add_argument("--icon-font", default=str(ICON_FONT))
    parser.add_argument("--work", default="target/keyglyphs", help="directory for the built font")
    args = parser.parse_args()

    glyphs = build_glyphs(TTFont(str(TEXT_FONT)), TTFont(args.icon_font))
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    font_path = work / "keyglyphs.otf"
    write_font(glyphs, font_path)
    charset = work / "charset.txt"
    charset.write_text(", ".join(str(cp) for cp in sorted(glyphs)))
    subprocess.run(
        [
            args.atlas_gen,
            "-font",
            str(font_path),
            "-charset",
            str(charset),
            "-type",
            "mtsdf",
            "-format",
            "png",
            "-size",
            "128",
            "-pxrange",
            "48",
            "-outerpxpadding",
            "32",
            "-yorigin",
            "bottom",
            "-square4",
            "-imageout",
            str(RES / "keyglyphs.png"),
            "-json",
            str(RES / "keyglyphs.json"),
        ],
        check=True,
    )
    atlas = json.loads((RES / "keyglyphs.json").read_text())
    print(
        f"{len(atlas['glyphs'])} glyphs, {atlas['atlas']['width']}x{atlas['atlas']['height']}"
        f" -> {RES / 'keyglyphs.png'}"
    )


if __name__ == "__main__":
    main()
