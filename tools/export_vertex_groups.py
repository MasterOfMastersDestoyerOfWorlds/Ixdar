"""Export all Blender vertex groups on the active mesh to JSON.

Usage in Blender:
    1. Open ~/Blends/Skull/Skull.blend (or whichever .blend has the groups).
    2. Select the mesh in the 3D viewport so it's the active object.
    3. Switch to the Scripting workspace, paste this file, run.

Writes ~/.ix/skull_vertex_groups.json:

    {
      "mesh_path": "voyage_skull_ref.obj",
      "vertex_count": 497452,
      "object_name": "Skull",
      "groups": {
        "<group-name>": [v0, v1, ...],   # raw Blender mesh vertex indices
        ...
      }
    }

Every vertex group on the active object is exported. A vertex is
included iff its weight in that group is >= 0.5.

The JSON's vertex indices must match the OBJ Ixdar loads. To stay in
sync, export the OBJ from this same .blend (File > Export > Wavefront,
Selection Only on, Apply Modifiers off, no triangulation done by
exporter — vertex_count in the JSON must equal the `v` line count in
the OBJ).
"""

import json
from pathlib import Path

import bpy


WEIGHT_THRESHOLD = 0.5
MESH_PATH_HINT = "voyage_skull_ref.obj"  # informational only; verifier uses its own path
OUTPUT_PATH = Path.home() / ".ix" / "skull_vertex_groups.json"


def export() -> None:
    obj = bpy.context.active_object
    if obj is None or obj.type != "MESH":
        raise RuntimeError(
            "Active object must be a mesh. Select it in the 3D viewport before running."
        )

    if not obj.vertex_groups:
        raise RuntimeError(
            f"No vertex groups on '{obj.name}'. Create at least one and assign weights."
        )

    group_index_to_name = {vg.index: vg.name for vg in obj.vertex_groups}
    groups_out: dict[str, list[int]] = {vg.name: [] for vg in obj.vertex_groups}

    mesh = obj.data
    for v in mesh.vertices:
        for g in v.groups:
            if g.weight < WEIGHT_THRESHOLD:
                continue
            name = group_index_to_name.get(g.group)
            if name is None:
                continue
            groups_out[name].append(v.index)

    payload = {
        "mesh_path": MESH_PATH_HINT,
        "vertex_count": len(mesh.vertices),
        "object_name": obj.name,
        "groups": groups_out,
    }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_PATH.open("w") as f:
        json.dump(payload, f, indent=2)

    total = sum(len(verts) for verts in groups_out.values())
    print(f"[export_vertex_groups] wrote {OUTPUT_PATH}")
    print(f"[export_vertex_groups] mesh '{obj.name}' vertices={len(mesh.vertices)}")
    print(f"[export_vertex_groups] groups={len(groups_out)} total-tagged-verts={total}")
    for name, verts in sorted(groups_out.items()):
        print(f"  {name}: {len(verts)} vertices")
    empty = [n for n, v in groups_out.items() if not v]
    if empty:
        print(f"[export_vertex_groups] WARN empty groups (weight<{WEIGHT_THRESHOLD} everywhere): {empty}")


if __name__ == "__main__":
    export()
