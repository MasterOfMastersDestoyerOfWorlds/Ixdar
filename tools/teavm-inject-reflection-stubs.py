#!/usr/bin/env python3
"""
TeaVM (strict:false) can emit reflection metadata that references class symbols
which were never linked into classes.js, causing runtime ReferenceError when
Class metadata initializes.

Scan classes.js for:
  - identifiers used as type / returnType / parameterTypes / $rt_arraycls(...)
  - minus identifiers defined via top-level `var name =` or `function name(`

Inject missing symbols immediately before `var $rt_seed =` as:
  var NAME = $rt_classWithoutFields(0);

Invoked from Maven (web-teavm profile) after the TeaVM compile goal.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# TeaVM-mangled class identifiers (exclude $rt_* runtime helpers and primitives).
_CLASS_NAME_RE = re.compile(r"^[a-z][a-z0-9]*_[A-Za-z0-9$]+$")

_SEED_LINE_RE = re.compile(
    r"^(\s*)var .*\$rt_seed\s*=\s*(\d+)\s*;\s*$", re.MULTILINE
)

_REF_PATTERNS = [
    re.compile(r"\btype\s*:\s*([A-Za-z0-9_$]+)", re.MULTILINE),
    re.compile(r"\breturnType\s*:\s*([A-Za-z0-9_$]+)", re.MULTILINE),
    re.compile(r"\$rt_arraycls\s*\(\s*([A-Za-z0-9_$]+)\s*\)", re.MULTILINE),
    re.compile(r"\bparameterTypes\s*:\s*\[([^\]]*)\]", re.MULTILINE),
]

_VAR_DEF_RE = re.compile(r"^\s*var\s+([A-Za-z0-9_$]+)\s*=", re.MULTILINE)
_FUNC_DEF_RE = re.compile(r"^\s*function\s+([A-Za-z0-9_$]+)\s*\(", re.MULTILINE)


def _split_parameter_list(inner: str) -> list[str]:
    out: list[str] = []
    for part in inner.split(","):
        p = part.strip()
        if not p or p.startswith("$rt_"):
            continue
        m = re.search(r"\$rt_arraycls\s*\(\s*([A-Za-z0-9_$]+)\s*\)", p)
        if m:
            out.append(m.group(1))
            continue
        if "(" in p:
            continue
        token = re.match(r"^([A-Za-z0-9_$]+)\s*$", p)
        if token:
            out.append(token.group(1))
    return out


def collect_references(js: str) -> set[str]:
    found: set[str] = set()
    for rx in _REF_PATTERNS[:3]:
        for m in rx.finditer(js):
            found.add(m.group(1))
    for m in _REF_PATTERNS[3].finditer(js):
        found.update(_split_parameter_list(m.group(1)))
    return found


def collect_defined(js: str) -> set[str]:
    d: set[str] = set()
    d.update(_VAR_DEF_RE.findall(js))
    d.update(_FUNC_DEF_RE.findall(js))
    return d


def should_stub(name: str) -> bool:
    if name.startswith("$"):
        return False
    if name.startswith("$rt_"):
        return False
    if name in {"true", "false", "null", "undefined", "void", "NaN"}:
        return False
    return bool(_CLASS_NAME_RE.match(name))


def patch_classes_js(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    m = _SEED_LINE_RE.search(text)
    if not m:
        print(f"error: no `var $rt_seed = N;` line in {path}", file=sys.stderr)
        return 1

    # Ignore the bootstrap line itself so prior $rt_classWithoutFields stubs are not
    # mistaken for real class definitions (would be dropped on the next run).
    text_wo_seed = text[: m.start()] + text[m.end() :]
    defined = collect_defined(text_wo_seed)
    referenced = collect_references(text)
    missing = sorted(
        n
        for n in referenced
        if should_stub(n) and n not in defined and n != "$rt_seed"
    )
    indent = m.group(1)
    seed_val = m.group(2)
    stubs = "".join(f"var {n} = $rt_classWithoutFields(0); " for n in missing)
    new_line = f"{indent}{stubs}var $rt_seed = {seed_val};\n"
    start, end = m.span()
    new_text = text[:start] + new_line + text[end:]
    path.write_text(new_text, encoding="utf-8")
    print(f"teavm stubs: injected {len(missing)} symbols into {path}")
    if missing:
        preview = ", ".join(missing[:40])
        if len(missing) > 40:
            preview += f", … (+{len(missing) - 40} more)"
        print(f"  {preview}")
    return 0


def main() -> None:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} path/to/classes.js", file=sys.stderr)
        sys.exit(2)
    sys.exit(patch_classes_js(Path(sys.argv[1])))


if __name__ == "__main__":
    main()
