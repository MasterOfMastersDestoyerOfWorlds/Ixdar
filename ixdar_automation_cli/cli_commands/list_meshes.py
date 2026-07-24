"""List the meshes a scene can be run against, by the short name ``run-scene`` accepts.

The point is to remove a filesystem hunt from the loop: this prints the names, their vertex and face
counts, and the paths, so picking the smallest mesh that still shows the behaviour is a read rather
than a `find`. Pass any of these names to ``run-scene --mesh`` or to a ``*.off`` property.

Usage:
    uv run ixdar-cli list-meshes
    uv run ixdar-cli list-meshes --all
    uv run ixdar-cli list-meshes --name fertility
"""

from ..cli_registry import CliCommandResult, cli_command
from ..mesh_catalog import discover_meshes

BYTES_PER_MEGABYTE = 1024 * 1024


@cli_command(name="list-meshes")
def list_meshes(
    all: bool = False,
    name: str = "",
) -> CliCommandResult:
    """List mesh files a scene can load, with the short names run-scene resolves.

    Sorted by face count, so the smallest mesh that still shows a behaviour is the first row.

    :param all: Include the ``_out_quad`` results and unloadable binary files, not just the inputs.
    :param name: Substring filter over the mesh name.
    """
    meshes = discover_meshes()
    unloadable = sorted({mesh["relPath"] for mesh in meshes if not mesh["loadable"]})
    if not all:
        meshes = [mesh for mesh in meshes if mesh["isInput"] and mesh["loadable"]]
    if name:
        needle = name.strip().lower()
        meshes = [mesh for mesh in meshes if needle in mesh["name"].lower()]

    rows: list[dict] = []
    seen: dict[tuple[str, int], dict] = {}
    for mesh in meshes:
        key = (mesh["alias"] or mesh["name"], mesh["bytes"])
        if key in seen:
            seen[key]["copies"].append(mesh["relPath"])
            continue
        row = {
            "name": mesh["alias"] or mesh["name"],
            "file": mesh["name"],
            "group": mesh["group"],
            "vertices": mesh["vertices"],
            "faces": mesh["faces"],
            "megabytes": round(mesh["bytes"] / BYTES_PER_MEGABYTE, 2),
            "relPath": mesh["relPath"],
            "loadable": mesh["loadable"],
            "copies": [],
        }
        seen[key] = row
        rows.append(row)

    listing = []
    for row in sorted(rows, key=lambda entry: (entry["faces"] or 1 << 30, entry["name"])):
        extra = f"  (+{len(row['copies'])} identical copy)" if row["copies"] else ""
        listing.append(
            f"{row['name']:<18} V={row['vertices']:<7} F={row['faces']:<7} "
            f"{row['megabytes']:>6}MB  {row['relPath']}{extra}")
    return CliCommandResult(payload={
        "count": len(rows),
        "unloadable": unloadable,
        "meshes": rows,
        "listing": listing,
    })
