#!/usr/bin/env python3
"""Parse an async-profiler flamegraph HTML and report self-time per frame.

The HTML stores a prefix-compressed string pool (`const cpool = [...]`,
reconstructed by `unpack`) and a stream of f()/u()/n() calls that build the
flame tree. `u` descends a level (child), `n` stays at the level (sibling),
`f` sets an explicit level. A frame's width is its inclusive sample count;
self-time = width minus the widths of its direct children.

Usage: python3 tools/parse_async_profile.py [profile.html] [--top N] [substr...]
"""
import re
import sys
from collections import defaultdict


def load_cpool(lines):
    start = next(i for i, l in enumerate(lines) if l.strip().startswith('const cpool = ['))
    raw = []
    for line in lines[start + 1:]:
        s = line.strip()
        if s.startswith('];'):
            break
        # strip trailing comma then the surrounding single quotes
        s = s[:-1] if s.endswith(',') else s
        s = s[1:-1]  # remove quotes
        s = s.replace("\\\\", "\\").replace("\\'", "'")
        raw.append(s)
    # unpack prefix compression
    for i in range(1, len(raw)):
        shared = ord(raw[i][0]) - 32
        raw[i] = raw[i - 1][:shared] + raw[i][1:]
    return raw


def parse_frames(lines, cpool):
    """Yield (level, width, name) for every frame, simulating the JS machine."""
    call = re.compile(r'^([fun])\((.*)\)\s*$')
    level0 = 0
    width0 = 0
    frames = []
    for line in lines:
        m = call.match(line.strip())
        if not m:
            continue
        kind, argstr = m.group(1), m.group(2)
        args = [a.strip() for a in argstr.split(',')] if argstr else []

        def arg(i):
            return int(args[i]) if i < len(args) and args[i] != '' else None

        if kind == 'f':
            key, level, _left, width = arg(0), arg(1), arg(2), arg(3)
        elif kind == 'u':
            key, level, width = arg(0), level0 + 1, arg(1)
        else:  # n
            key, level, width = arg(0), level0, arg(1)

        width0 = width if width is not None else width0
        level0 = level
        name = cpool[key >> 3]
        frames.append((level, width0, name))
    return frames


def self_times(frames):
    self_by = defaultdict(int)
    incl_by = defaultdict(int)
    total = frames[0][1] if frames else 0
    stack = []  # (level, name, self_remaining)
    for level, width, name in frames:
        while stack and stack[-1][0] >= level:
            lv, nm, sf = stack.pop()
            self_by[nm] += sf[0]
        if stack:
            stack[-1][2][0] -= width
        incl_by[name] += width
        stack.append((level, name, [width]))
    while stack:
        lv, nm, sf = stack.pop()
        self_by[nm] += sf[0]
    return total, self_by, incl_by


def main():
    argv = sys.argv[1:]
    path = 'profile.html'
    top = 30
    subs = []
    i = 0
    while i < len(argv):
        if argv[i] == '--top':
            top = int(argv[i + 1]); i += 2
        elif argv[i].endswith('.html'):
            path = argv[i]; i += 1
        else:
            subs.append(argv[i]); i += 1

    with open(path) as fh:
        lines = fh.readlines()
    cpool = load_cpool(lines)
    # The f()/u()/n() data stream begins right after `unpack(cpool);`; earlier
    # matches are the builder-function bodies, not data.
    data_start = next(i for i, l in enumerate(lines) if l.strip() == 'unpack(cpool);')
    frames = parse_frames(lines[data_start + 1:], cpool)
    total, self_by, incl_by = self_times(frames)

    print(f"total samples: {total}\n")
    print("=== top self-time frames ===")
    for name, sf in sorted(self_by.items(), key=lambda kv: -kv[1])[:top]:
        print(f"{sf:8d}  {100*sf/total:5.1f}%  {name}")

    if subs:
        print("\n=== matching frames (inclusive / self) ===")
        for name in sorted(incl_by, key=lambda n: -incl_by[n]):
            if any(s in name for s in subs):
                print(f"incl={incl_by[name]:8d} ({100*incl_by[name]/total:5.1f}%)  "
                      f"self={self_by[name]:8d} ({100*self_by[name]/total:5.1f}%)  {name}")


if __name__ == '__main__':
    main()
