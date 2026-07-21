"""Parse an async-profiler flamegraph HTML and report self-time per frame.

The HTML stores a prefix-compressed string pool (``const cpool = [...]``, reconstructed by
``unpack``) and a stream of ``f()``/``u()``/``n()`` calls that build the flame tree. ``u`` descends a
level (child), ``n`` stays at the level (sibling), ``f`` sets an explicit level. A frame's width is
its inclusive sample count; self-time is that width minus the widths of its direct children.

Self-time is the number that says where the CPU actually was. A frame with high inclusive but low
self time is not itself expensive — its callees, or the number of times it is called, are.

This lives in the CLI package so ``run-scene`` can report hot methods straight after a profiled run;
"""

import re
import sys
from collections import defaultdict

DEFAULT_PROFILE = "profile.html"
DEFAULT_TOP = 30


def load_cpool(lines: list[str]) -> list[str]:
    """Reconstruct the prefix-compressed string pool of frame names.

    :param lines: Lines of the flamegraph HTML.
    :return: The decompressed frame-name pool, in pool order.
    """
    start = next(i for i, line in enumerate(lines) if line.strip().startswith("const cpool = ["))
    raw: list[str] = []
    for line in lines[start + 1:]:
        stripped = line.strip()
        if stripped.startswith("];"):
            break
        stripped = stripped[:-1] if stripped.endswith(",") else stripped
        stripped = stripped[1:-1]
        stripped = stripped.replace("\\\\", "\\").replace("\\'", "'")
        raw.append(stripped)
    for index in range(1, len(raw)):
        shared = ord(raw[index][0]) - 32
        raw[index] = raw[index - 1][:shared] + raw[index][1:]
    return raw


def parse_frames(lines: list[str], cpool: list[str]) -> list[tuple[int, int, str]]:
    """Replay the ``f()``/``u()``/``n()`` stream into flat frames.

    :param lines: Lines of the data stream (everything after ``unpack(cpool);``).
    :param cpool: Decompressed frame-name pool.
    :return: ``(level, width, name)`` for every frame, in document order.
    """
    call = re.compile(r"^([fun])\((.*)\)\s*$")
    level0 = 0
    width0 = 0
    frames: list[tuple[int, int, str]] = []
    for line in lines:
        matched = call.match(line.strip())
        if not matched:
            continue
        kind, argstr = matched.group(1), matched.group(2)
        args = [a.strip() for a in argstr.split(",")] if argstr else []

        def arg(index: int) -> int | None:
            return int(args[index]) if index < len(args) and args[index] != "" else None

        if kind == "f":
            key, level, width = arg(0), arg(1), arg(3)
        elif kind == "u":
            key, level, width = arg(0), level0 + 1, arg(1)
        else:
            key, level, width = arg(0), level0, arg(1)

        width0 = width if width is not None else width0
        level0 = level
        frames.append((level, width0, cpool[key >> 3]))
    return frames


def self_times(frames: list[tuple[int, int, str]]) -> tuple[int, dict, dict]:
    """Fold frames into per-name self and inclusive sample counts.

    :param frames: Flat frames from :func:`parse_frames`.
    :return: ``(total_samples, self_by_name, inclusive_by_name)``.
    """
    self_by: dict[str, int] = defaultdict(int)
    incl_by: dict[str, int] = defaultdict(int)
    total = frames[0][1] if frames else 0
    stack: list[tuple[int, str, list[int]]] = []
    for level, width, name in frames:
        while stack and stack[-1][0] >= level:
            _, popped_name, remaining = stack.pop()
            self_by[popped_name] += remaining[0]
        if stack:
            stack[-1][2][0] -= width
        incl_by[name] += width
        stack.append((level, name, [width]))
    while stack:
        _, popped_name, remaining = stack.pop()
        self_by[popped_name] += remaining[0]
    return total, self_by, incl_by


def analyze(path: str) -> tuple[int, dict, dict]:
    """Parse a flamegraph HTML into sample totals.

    :param path: Path to the async-profiler HTML.
    :return: ``(total_samples, self_by_name, inclusive_by_name)``.
    """
    with open(path, encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
    cpool = load_cpool(lines)
    data_start = next(i for i, line in enumerate(lines) if line.strip() == "unpack(cpool);")
    return self_times(parse_frames(lines[data_start + 1:], cpool))


def format_hot_methods(path: str, top: int = DEFAULT_TOP, contains: list[str] | None = None) -> str:
    """Render the self-time ranking, and optionally an inclusive/self table for named frames.

    :param path: Path to the async-profiler HTML.
    :param top: How many self-time rows to report.
    :param contains: Substrings; matching frames get an extra inclusive-vs-self table.
    :return: A printable report.
    """
    total, self_by, incl_by = analyze(path)
    if not total:
        return f"no samples in {path}"
    out = [f"total samples: {total}", "", "=== top self-time frames ==="]
    for name, samples in sorted(self_by.items(), key=lambda item: -item[1])[:top]:
        out.append(f"{samples:8d}  {100 * samples / total:5.1f}%  {name}")
    if contains:
        out.extend(["", "=== matching frames (inclusive / self) ==="])
        for name in sorted(incl_by, key=lambda key: -incl_by[key]):
            if any(needle in name for needle in contains):
                out.append(
                    f"incl={incl_by[name]:8d} ({100 * incl_by[name] / total:5.1f}%)  "
                    f"self={self_by[name]:8d} ({100 * self_by[name] / total:5.1f}%)  {name}"
                )
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    """Command-line entry point: ``parse_async_profile.py [profile.html] [--top N] [substr...]``.

    :param argv: Arguments after the program name; defaults to ``sys.argv[1:]``.
    :return: Process exit code.
    """
    args = list(sys.argv[1:] if argv is None else argv)
    path = DEFAULT_PROFILE
    top = DEFAULT_TOP
    contains: list[str] = []
    index = 0
    while index < len(args):
        if args[index] == "--top":
            top = int(args[index + 1])
            index += 2
        elif args[index].endswith(".html"):
            path = args[index]
            index += 1
        else:
            contains.append(args[index])
            index += 1
    print(format_hot_methods(path, top=top, contains=contains))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
