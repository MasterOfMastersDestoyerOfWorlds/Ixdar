"""Canonical mesh fingerprint; must match Java `MeshCanonicalFingerprint`."""

from __future__ import annotations

import hashlib
import struct

ALGORITHM_ID = "ixdar-mesh-fingerprint-v1"
POSITION_ROUND_SCALE = 1.0e5


def round_coord(v: float) -> float:
    return round(v * POSITION_ROUND_SCALE) / POSITION_ROUND_SCALE


def _corner_from_vertex(vertices: list[tuple[float, float, float]], idx: int) -> list[float]:
    x, y, z = vertices[idx]
    return [round_coord(x), round_coord(y), round_coord(z)]


def _sort_corners(a: list[float], b: list[float], c: list[float]) -> tuple[list[float], list[float], list[float]]:
    t = sorted([a, b, c], key=lambda p: (p[0], p[1], p[2]))
    return (t[0], t[1], t[2])


def _triangle_sort_key(tr: tuple[list[float], list[float], list[float]]) -> tuple:
    return (tuple(tr[0]), tuple(tr[1]), tuple(tr[2]))


def sha256_hex_from_vertices_and_triangles(
    vertices: list[tuple[float, float, float]],
    triangles: list[tuple[int, int, int]],
) -> str:
    """Same algorithm as Java `MeshCanonicalFingerprint.sha256Hex`."""
    sorted_tris: list[tuple[list[float], list[float], list[float]]] = []
    for i0, i1, i2 in triangles:
        c0 = _corner_from_vertex(vertices, i0)
        c1 = _corner_from_vertex(vertices, i1)
        c2 = _corner_from_vertex(vertices, i2)
        sorted_tris.append(_sort_corners(c0, c1, c2))
    sorted_tris.sort(key=_triangle_sort_key)
    buf = bytearray()
    for tr in sorted_tris:
        for c in tr:
            buf += struct.pack(">fff", c[0], c[1], c[2])
    return hashlib.sha256(buf).hexdigest()


def iter_obj_triangles(path: str) -> tuple[list[tuple[float, float, float]], list[tuple[int, int, int]]]:
    """Parse OBJ line-by-line: vertices and fan-triangulated faces (matches Java fan order)."""
    vertices: list[tuple[float, float, float]] = []
    triangles: list[tuple[int, int, int]] = []
    with open(path, "r", encoding="utf-8", errors="replace", buffering=1024 * 1024) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if not parts:
                continue
            if parts[0] == "v" and len(parts) >= 4:
                vertices.append((float(parts[1]), float(parts[2]), float(parts[3])))
            elif parts[0] == "f" and len(parts) >= 4:
                idxs: list[int] = []
                for p in parts[1:]:
                    vi_str = p.split("/")[0]
                    vi = int(vi_str)
                    if vi < 0:
                        idxs.append(len(vertices) + vi)
                    else:
                        idxs.append(vi - 1)
                for j in range(1, len(idxs) - 1):
                    triangles.append((idxs[0], idxs[j], idxs[j + 1]))
    return vertices, triangles


def sha256_hex_from_obj_path(path: str) -> str:
    vertices, triangles = iter_obj_triangles(path)
    return sha256_hex_from_vertices_and_triangles(vertices, triangles)


def triangle_count_from_obj_path(path: str) -> int:
    _, triangles = iter_obj_triangles(path)
    return len(triangles)
