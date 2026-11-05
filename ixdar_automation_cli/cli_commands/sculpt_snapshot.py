"""Snapshot a stroke-sculpt round into a self-contained directory: the DSL plus every file it reads.

The stroke-sculpt skill snapshots each round to ``tmp/rounds/round<NN>/`` so a judge can score it
and ``sculpt-animate --rounds`` can replay it. A plain copy of the layer directory holds DSL paths
that point back at the live layers (``svg="dsl/skull/jaw.svg"``, ``load_mesh(path=...)``) and may
hold a second DSL (``base.dsl``). This command copies the one sculpt DSL and every file its nodes
name (stroke_sculpt and silhouette_hull ``svg``, load_mesh ``path``), with the ``.strokes`` sidecar
sculpt-sync keeps beside a stroke_sculpt SVG, into the round directory and
points the copied DSL at those copies by absolute path, so the round evaluates from any checkout
without touching the live files.

Usage:
    uv run ixdar-cli sculpt-snapshot --dsl ixdar-app/src/main/resources/dsl/skull3/skull.dsl --rounds tmp/rounds
"""

import os
import re
import shutil
from pathlib import Path

from ..checkout import checkout_root
from ..cli_registry import CliCommandResult, cli_command
from .sculpt_dsl import FILE_ARGUMENTS, dsl_nodes, resolve_resource, unquoted

ROUND_DIRECTORY = re.compile(r"^round(\d+)$")
SIDECAR_SUFFIX = ".strokes"
SIDECAR_NODES = {"stroke_sculpt"}
SYNCED_MARK = "data-pts="


@cli_command(name="sculpt-snapshot")
def sculpt_snapshot(dsl: str, rounds: str = "tmp/rounds", number: int = 0) -> CliCommandResult:
    """Copy a sculpt DSL and every file its nodes read into the next tmp/rounds/round<NN>/, the copy's paths pointing at the copies.

    The round holds exactly one .dsl (the sculpt's own name) and each svg= / path= file under its own
    name, a stroke_sculpt SVG's .strokes sidecar beside it; two different files with one name are
    refused, and so is a synced SVG whose sidecar is missing. The copied DSL names them by absolute path,
    so it evaluates anywhere (``model --path``, ``mesh-dsl --name``, a judge's run) and
    ``sculpt-animate --rounds`` replays it.

    :param dsl: Sculpt DSL file, absolute or relative to the checkout.
    :param rounds: Directory holding the round<NN>/ snapshots.
    :param number: Round number to write; default one past the highest existing.
    """
    root = str(checkout_root())
    dsl_path = resolve_resource(dsl, root)
    rounds_dir = rounds if os.path.isabs(rounds) else os.path.join(root, rounds)
    os.makedirs(rounds_dir, exist_ok=True)
    existing = [int(match.group(1)) for name in os.listdir(rounds_dir) if (match := ROUND_DIRECTORY.match(name))]
    number = number or (max(existing, default=0) + 1)
    snapshot = os.path.join(rounds_dir, f"round{number:02d}")
    if os.path.exists(snapshot):
        return CliCommandResult(payload={"ok": False, "error": f"{snapshot} exists; pass another --number"},
                                exit_code=2)
    lines = open(dsl_path, encoding="utf-8").read().splitlines()
    copies: dict[str, str] = {}
    for node in dsl_nodes(lines):
        argument = FILE_ARGUMENTS.get(node["type"])
        if not argument or argument not in node["arguments"]:
            continue
        written = unquoted(node["arguments"][argument])
        source = resolve_resource(written, root)
        # A stroke layer is its SVG plus the sidecar sculpt-sync keeps beside it, which the node reads
        # from the SVG's own folder: the copy needs both, or a synced SVG refuses to load.
        sidecar = os.path.splitext(source)[0] + SIDECAR_SUFFIX
        for file in [source] + ([sidecar] if node["type"] in SIDECAR_NODES and os.path.isfile(sidecar) else []):
            name = os.path.basename(file)
            if copies.get(name, file) != file:
                return CliCommandResult(payload={"ok": False, "error": f"two files named {name}: {copies[name]} and "
                                                 f"{file}"}, exit_code=2)
            copies[name] = file
        target = os.path.abspath(os.path.join(snapshot, os.path.basename(source)))
        lines[node["line"]] = lines[node["line"]].replace(f'{argument}="{written}"', f'{argument}="{target}"', 1)
    os.makedirs(snapshot)
    for name, source in copies.items():
        shutil.copy2(source, os.path.join(snapshot, name))
    out = os.path.join(snapshot, os.path.basename(dsl_path))
    with open(out, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    unloadable = [name for name in copies if name.endswith(".svg") and SYNCED_MARK in Path(
        snapshot, name).read_text(encoding="utf-8") and os.path.splitext(name)[0] + SIDECAR_SUFFIX not in copies]
    if unloadable:
        return CliCommandResult(payload={"ok": False, "snapshot": snapshot, "error": "written by sculpt-sync but no "
                                         f"sidecar beside the original to copy: {', '.join(unloadable)}; the snapshot "
                                         "will not load"}, exit_code=2)
    return CliCommandResult(payload={"ok": True, "round": number, "snapshot": snapshot, "dsl": out,
                                     "files": sorted(copies)})
