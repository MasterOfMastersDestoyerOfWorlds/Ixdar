"""Minimal standard-library PNG decoder and pixel statistics for screenshot inspection.

The automation CLI has no third-party dependencies and Pillow is not installed on the machines
agents run on, so comparing two screenshots used to fall back to ``sha256sum`` (which only answers
"identical or not") or to a bespoke pixel counter written from scratch each time. This module reads
the PNGs the automation server writes — 8-bit, non-interlaced, produced by Java's ``ImageIO`` — and
exposes the two measurements that actually answer "did the render change": a root-mean-square error
and a count of pixels differing beyond a fuzz threshold.
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

CHANNELS_BY_COLOR_TYPE = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}

MAX_CHANNEL_VALUE = 255


@dataclass(frozen=True)
class PngImage:
    """A decoded 8-bit PNG as a flat row-major RGB byte string."""

    width: int
    height: int
    rgb: bytes

    def pixel_count(self) -> int:
        """Number of pixels in the image.

        :return: width times height
        """
        return self.width * self.height


def read_png(path: str | Path) -> PngImage:
    """Decode an 8-bit non-interlaced PNG into RGB bytes.

    Grayscale, palette and alpha-carrying images are expanded to RGB; alpha is dropped because the
    framebuffer captures are opaque.

    :param path: path to the PNG file
    :return: the decoded image
    """
    data = Path(path).read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError(f"{path} is not a PNG file")

    width = height = bit_depth = color_type = interlace = 0
    palette = b""
    compressed = bytearray()
    offset = len(PNG_SIGNATURE)
    while offset + 8 <= len(data):
        (length,) = struct.unpack(">I", data[offset:offset + 4])
        chunk_type = data[offset + 4:offset + 8]
        body = data[offset + 8:offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif chunk_type == b"PLTE":
            palette = body
        elif chunk_type == b"IDAT":
            compressed += body
        elif chunk_type == b"IEND":
            break

    if bit_depth != 8:
        raise ValueError(f"{path}: only 8-bit PNGs are supported, got bit depth {bit_depth}")
    if interlace:
        raise ValueError(f"{path}: interlaced PNGs are not supported")
    if color_type not in CHANNELS_BY_COLOR_TYPE:
        raise ValueError(f"{path}: unsupported PNG colour type {color_type}")

    channels = CHANNELS_BY_COLOR_TYPE[color_type]
    raw = zlib.decompress(bytes(compressed))
    scanlines = _unfilter(raw, width, height, channels)
    return PngImage(width, height, _to_rgb(scanlines, width, height, color_type, channels, palette))


def _unfilter(raw: bytes, width: int, height: int, channels: int) -> bytearray:
    """Reverse the per-scanline PNG filters, returning the raw sample bytes.

    :param raw: inflated IDAT bytes, one filter byte per scanline
    :param width: image width in pixels
    :param height: image height in pixels
    :param channels: samples per pixel
    :return: unfiltered samples, ``height * width * channels`` bytes
    """
    stride = width * channels
    expected = height * (stride + 1)
    if len(raw) < expected:
        raise ValueError(f"truncated PNG data: expected {expected} bytes, got {len(raw)}")

    output = bytearray(height * stride)
    previous = bytearray(stride)
    for row in range(height):
        source = row * (stride + 1)
        filter_type = raw[source]
        line = bytearray(raw[source + 1:source + 1 + stride])
        for index in range(stride):
            left = line[index - channels] if index >= channels else 0
            up = previous[index]
            upper_left = previous[index - channels] if index >= channels else 0
            if filter_type == 1:
                line[index] = (line[index] + left) & MAX_CHANNEL_VALUE
            elif filter_type == 2:
                line[index] = (line[index] + up) & MAX_CHANNEL_VALUE
            elif filter_type == 3:
                line[index] = (line[index] + ((left + up) >> 1)) & MAX_CHANNEL_VALUE
            elif filter_type == 4:
                line[index] = (line[index] + _paeth(left, up, upper_left)) & MAX_CHANNEL_VALUE
            elif filter_type != 0:
                raise ValueError(f"unknown PNG filter type {filter_type} on row {row}")
        output[row * stride:(row + 1) * stride] = line
        previous = line
    return output


def _paeth(left: int, up: int, upper_left: int) -> int:
    """The PNG Paeth predictor: pick whichever neighbour the linear estimate is closest to.

    :param left: sample to the left
    :param up: sample above
    :param upper_left: sample above and to the left
    :return: the predicted sample
    """
    estimate = left + up - upper_left
    distance_left = abs(estimate - left)
    distance_up = abs(estimate - up)
    distance_upper_left = abs(estimate - upper_left)
    if distance_left <= distance_up and distance_left <= distance_upper_left:
        return left
    if distance_up <= distance_upper_left:
        return up
    return upper_left


def _to_rgb(samples: bytearray, width: int, height: int, color_type: int, channels: int,
            palette: bytes) -> bytes:
    """Expand decoded samples of any supported colour type into packed RGB triples.

    :param samples: unfiltered samples
    :param width: image width in pixels
    :param height: image height in pixels
    :param color_type: PNG colour type from the IHDR chunk
    :param channels: samples per pixel
    :param palette: PLTE chunk contents, used only for colour type 3
    :return: ``width * height * 3`` RGB bytes
    """
    if color_type == 2:
        return bytes(samples)
    pixels = width * height
    rgb = bytearray(pixels * 3)
    for pixel in range(pixels):
        source = pixel * channels
        if color_type == 3:
            entry = samples[source] * 3
            rgb[pixel * 3:pixel * 3 + 3] = palette[entry:entry + 3]
        elif color_type in (0, 4):
            gray = samples[source]
            rgb[pixel * 3] = gray
            rgb[pixel * 3 + 1] = gray
            rgb[pixel * 3 + 2] = gray
        else:
            rgb[pixel * 3:pixel * 3 + 3] = samples[source:source + 3]
    return bytes(rgb)


_SQUARES = tuple(value * value for value in range(256))


def image_statistics(image: PngImage) -> dict:
    """Summarize an image's brightness, enough to tell a real render from a blank frame.

    :param image: decoded image
    :return: pixel dimensions, mean, minimum and maximum channel values, and a blank flag that is
        true when every sample in the image is the same value
    """
    rgb = image.rgb
    if not rgb:
        raise ValueError("image has no pixels")
    minimum = min(rgb)
    maximum = max(rgb)
    return {
        "width": image.width,
        "height": image.height,
        "meanChannelValue": sum(rgb) / len(rgb),
        "minChannelValue": minimum,
        "maxChannelValue": maximum,
        "blank": minimum == maximum,
    }


def compare_images(first: PngImage, second: PngImage, fuzz: int) -> dict:
    """Measure how far two same-sized images differ.

    Works one colour plane at a time so the per-byte arithmetic stays inside C-level ``map`` and
    ``sum`` calls; a full-resolution multiview composite would take minutes in a Python loop.

    :param first: baseline image
    :param second: comparison image
    :param fuzz: per-channel absolute difference a pixel may show before it counts as different
    :return: RMSE over all channels, the differing-pixel count and fraction, and the largest delta
    """
    if (first.width, first.height) != (second.width, second.height):
        raise ValueError(
            f"image sizes differ: {first.width}x{first.height} vs {second.width}x{second.height}"
        )
    pixels = first.pixel_count()
    left = first.rgb
    right = second.rgb
    if left == right:
        return {
            "width": first.width,
            "height": first.height,
            "rmse": 0.0,
            "differingPixels": 0,
            "differingFraction": 0.0,
            "maxChannelDelta": 0,
            "identical": True,
        }

    squared_error = 0
    planes = []
    for channel in range(3):
        deltas = bytes(
            map(abs, map(int.__sub__, left[channel::3], right[channel::3]))
        )
        squared_error += sum(map(_SQUARES.__getitem__, deltas))
        planes.append(deltas)

    per_pixel_delta = bytes(map(max, planes[0], planes[1], planes[2]))
    over_fuzz = bytes(1 if value > fuzz else 0 for value in range(256))
    differing_pixels = sum(per_pixel_delta.translate(over_fuzz))
    return {
        "width": first.width,
        "height": first.height,
        "rmse": (squared_error / len(left)) ** 0.5,
        "differingPixels": differing_pixels,
        "differingFraction": differing_pixels / max(1, pixels),
        "maxChannelDelta": max(per_pixel_delta),
        "identical": False,
    }
