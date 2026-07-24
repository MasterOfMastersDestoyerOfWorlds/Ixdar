"""Index of the mesh files a scene can be run against, addressed by short name.

Scenes take a mesh through a ``*.off`` system property, and the paths are long, duplicated across
figure directories, and — because a scene's own default is written relative to ``ixdar-app`` while
``run-scene`` launches the JVM from the repo root — easy to get wrong in a way that only shows up as
a ``NoSuchFileException`` after a build. This module turns ``fertility`` into an absolute path so
neither a person nor an agent has to go looking.

Names resolve in three widening steps: an existing path is passed through untouched, then an exact
file stem (``fertility_in_tri``), then a short alias (``fertility``). Short aliases are minted only
from the ``_in_tri`` inputs, because those are the triangle meshes the pipeline actually consumes —
the ``_out_quad`` files are its published results, and aliasing both would make every name ambiguous.
"""

import os

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))

MESH_ROOTS = [os.path.join(REPO_DIR, "ixdar-app", "test", "resources", "quadlayout")]

MESH_EXTENSIONS = (".off", ".obj")

INPUT_SUFFIX = "_in_tri"

OFF_MAGIC = "OFF"

BINARY_MARKER = "BINARY"


def _off_header(path: str) -> tuple[int, int, bool]:
    """Read an OFF file's vertex and face counts, and whether it is the binary variant.

    ``MeshLoader`` reads every format through ``Files.readString``, so a binary OFF cannot be loaded
    at all — detecting it here is what keeps an unusable mesh out of the runnable list.

    :param path: Path to an ``.off`` file.
    :return: ``(vertices, faces, isBinary)``; counts are zero when the header cannot be read.
    """
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            first = handle.readline().strip()
            if OFF_MAGIC not in first.upper():
                return 0, 0, False
            if BINARY_MARKER in first.upper():
                return 0, 0, True
            for line in handle:
                stripped = line.strip()
                if not stripped or stripped.startswith("#"):
                    continue
                parts = stripped.split()
                return int(parts[0]), int(parts[1]), False
    except (OSError, ValueError, IndexError):
        return 0, 0, False
    return 0, 0, False


def discover_meshes() -> list[dict]:
    """Find every mesh under the repository's mesh roots.

    :return: One entry per file with ``name``, ``alias``, ``path``, ``relPath``, ``group``,
        ``bytes``, ``vertices``, ``faces``, ``isInput`` and ``loadable``, sorted by group then name.
    """
    meshes: list[dict] = []
    for root in MESH_ROOTS:
        for directory, _subdirectories, files in os.walk(root):
            for filename in sorted(files):
                stem, extension = os.path.splitext(filename)
                if extension.lower() not in MESH_EXTENSIONS:
                    continue
                path = os.path.join(directory, filename)
                is_input = stem.endswith(INPUT_SUFFIX)
                vertices, faces, is_binary = (
                    _off_header(path) if extension.lower() == ".off" else (0, 0, False))
                meshes.append({
                    "name": stem,
                    "alias": stem[:-len(INPUT_SUFFIX)] if is_input else "",
                    "path": path,
                    "relPath": os.path.relpath(path, REPO_DIR),
                    "group": os.path.relpath(directory, root),
                    "bytes": os.path.getsize(path),
                    "vertices": vertices,
                    "faces": faces,
                    "isInput": is_input,
                    "loadable": not is_binary,
                })
    meshes.sort(key=lambda entry: (entry["group"], entry["name"]))
    return meshes


def _index_by_key(meshes: list[dict]) -> dict[str, list[dict]]:
    """Build the lookup from every addressable name to the meshes that answer to it.

    :param meshes: Discovered meshes.
    :return: Map from stem or short alias to the matching entries.
    """
    index: dict[str, list[dict]] = {}
    for mesh in meshes:
        for key in filter(None, (mesh["name"], mesh["alias"])):
            index.setdefault(key.lower(), []).append(mesh)
    return index


def resolve_mesh(name: str) -> str:
    """Resolve a mesh name, alias or path to an absolute path.

    Copies of one mesh in different figure directories are common and byte-identical, so a name
    matching several files of the same size resolves rather than erroring; only a genuine conflict
    between different files is ambiguous.

    :param name: An existing path, a file stem, or a short alias such as ``fertility``.
    :return: Absolute path to the mesh file.
    :raises ValueError: When the name matches nothing, matches differing files, or names a mesh
        ``MeshLoader`` cannot read.
    """
    if os.path.exists(name):
        return os.path.abspath(name)
    repo_relative = os.path.join(REPO_DIR, name)
    if os.path.exists(repo_relative):
        return os.path.abspath(repo_relative)

    meshes = discover_meshes()
    matches = _index_by_key(meshes).get(name.strip().lower(), [])
    if not matches:
        inputs = sorted({mesh["alias"] for mesh in meshes if mesh["alias"] and mesh["loadable"]})
        raise ValueError(
            f"unknown mesh {name!r}; run `ixdar-cli list-meshes` to see them all. Inputs: "
            + ", ".join(inputs))
    if not any(mesh["loadable"] for mesh in matches):
        raise ValueError(
            f"mesh {name!r} is a binary OFF file, which MeshLoader cannot read — it loads every "
            "format through Files.readString")
    loadable = [mesh for mesh in matches if mesh["loadable"]]
    if len({mesh["bytes"] for mesh in loadable}) > 1:
        candidates = ", ".join(mesh["relPath"] for mesh in loadable)
        raise ValueError(f"mesh {name!r} is ambiguous between differing files: {candidates}")
    return loadable[0]["path"]


def resolve_off_properties(properties: list[str]) -> list[str]:
    """Rewrite any ``*.off`` system property whose value is a mesh name into a full path.

    :param properties: ``key=value`` system properties as given on the command line.
    :return: The same list with mesh-valued properties resolved.
    :raises ValueError: When a named mesh cannot be resolved.
    """
    resolved = []
    for entry in properties:
        key, separator, value = entry.partition("=")
        if separator and key.lower().endswith(".off") and value:
            resolved.append(f"{key}={resolve_mesh(value)}")
        else:
            resolved.append(entry)
    return resolved
