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

Besides the checked-in quad-layout corpus, the directory named by ``IXDAR_MODEL_DIR`` is scanned
when set, so pointing it at a folder of glTF scans lists them next to the repo meshes.
"""

import json
import os
import struct

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))

MODEL_DIR_VARIABLE = "IXDAR_MODEL_DIR"

MESH_EXTENSIONS = (".off", ".obj", ".ply", ".glb", ".gltf")

GLTF_EXTENSIONS = (".glb", ".gltf")

INPUT_SUFFIX = "_in_tri"

OFF_MAGIC = "OFF"

BINARY_MARKER = "BINARY"

GLB_HEADER_BYTES = 12

GLB_CHUNK_HEADER_BYTES = 8

GLB_MAGIC = b"glTF"

VERTICES_PER_TRIANGLE = 3


def mesh_roots() -> list[str]:
    """The directories ``discover_meshes`` walks: the repo corpus plus ``IXDAR_MODEL_DIR`` when set.

    :return: Existing directories, the repo corpus first.
    """
    roots = [os.path.join(REPO_DIR, "ixdar-app", "test", "resources", "quadlayout")]
    model_dir = os.environ.get(MODEL_DIR_VARIABLE, "").strip()
    if model_dir:
        expanded = os.path.abspath(os.path.expanduser(model_dir))
        if os.path.isdir(expanded) and expanded not in roots:
            roots.append(expanded)
    return roots


def _gltf_document(path: str) -> dict:
    """Read the JSON document of a ``.gltf`` file or the JSON chunk of a ``.glb`` container.

    :param path: Path to a glTF file.
    :return: The parsed document, or ``{}`` when the file is not readable glTF.
    """
    try:
        with open(path, "rb") as handle:
            if path.lower().endswith(".gltf"):
                return json.load(handle)
            header = handle.read(GLB_HEADER_BYTES)
            if len(header) < GLB_HEADER_BYTES or header[:4] != GLB_MAGIC:
                return {}
            chunk_length, _chunk_type = struct.unpack("<II", handle.read(GLB_CHUNK_HEADER_BYTES))
            return json.loads(handle.read(chunk_length))
    except (OSError, ValueError, struct.error):
        return {}


def _gltf_counts(path: str) -> tuple[int, int]:
    """Sum vertex and triangle counts over every primitive of a glTF file from its accessors.

    Only the JSON is read, so a 40 MB scan costs a few hundred kilobytes of I/O; a primitive without
    indices contributes its vertex count divided by three.

    These are the file's own accessor counts. ``GltfMeshParser`` welds bitwise-identical positions on
    import — glTF splits them only to give one position several UVs — so the loaded mesh has fewer
    vertices than this reports (IMG_4109: 622,652 in the file, 468,350 loaded) and the same faces.

    :param path: Path to a ``.glb`` or ``.gltf`` file.
    :return: ``(vertices, faces)``; zeros when the document cannot be read.
    """
    document = _gltf_document(path)
    accessors = document.get("accessors", [])
    vertices = 0
    faces = 0
    for mesh in document.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            position = primitive.get("attributes", {}).get("POSITION")
            count = accessors[position]["count"] if position is not None and position < len(accessors) else 0
            vertices += count
            indices = primitive.get("indices")
            if indices is not None and indices < len(accessors):
                faces += accessors[indices]["count"] // VERTICES_PER_TRIANGLE
            else:
                faces += count // VERTICES_PER_TRIANGLE
    return vertices, faces


def _off_header(path: str) -> tuple[int, int, bool]:
    """Read an OFF file's vertex and face counts, and whether it is the binary variant.

    ``MeshLoader`` reads the text formats through ``Files.readAllBytes`` as UTF-8, so a binary OFF
    cannot be loaded at all — detecting it here is what keeps an unusable mesh out of the runnable
    list.

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
    """Find every mesh under the repository's mesh roots and ``IXDAR_MODEL_DIR``.

    glTF scans count as inputs (they are whole models, never pipeline output) and are addressed by
    their file stem, e.g. ``IMG_4109``.

    :return: One entry per file with ``name``, ``alias``, ``path``, ``relPath``, ``group``,
        ``bytes``, ``vertices``, ``faces``, ``isInput`` and ``loadable``, sorted by group then name.
    """
    meshes: list[dict] = []
    for root in mesh_roots():
        for directory, _subdirectories, files in os.walk(root):
            for filename in sorted(files):
                stem, extension = os.path.splitext(filename)
                if extension.lower() not in MESH_EXTENSIONS:
                    continue
                path = os.path.join(directory, filename)
                is_input = stem.endswith(INPUT_SUFFIX) or extension.lower() in GLTF_EXTENSIONS
                if extension.lower() == ".off":
                    vertices, faces, is_binary = _off_header(path)
                elif extension.lower() in GLTF_EXTENSIONS:
                    (vertices, faces), is_binary = _gltf_counts(path), False
                else:
                    vertices, faces, is_binary = 0, 0, False
                meshes.append({
                    "name": stem,
                    "alias": stem[:-len(INPUT_SUFFIX)] if stem.endswith(INPUT_SUFFIX) else "",
                    "path": path,
                    "relPath": os.path.relpath(path, REPO_DIR) if path.startswith(REPO_DIR) else path,
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
            f"mesh {name!r} is a binary OFF file, which MeshLoader cannot read — it loads the text "
            "formats as UTF-8")
    loadable = [mesh for mesh in matches if mesh["loadable"]]
    if len({mesh["bytes"] for mesh in loadable}) > 1:
        candidates = ", ".join(mesh["relPath"] for mesh in loadable)
        raise ValueError(f"mesh {name!r} is ambiguous between differing files: {candidates}")
    return loadable[0]["path"]


def mesh_size(name: str) -> tuple[int, int]:
    """Vertex and face counts of a catalogued mesh, from its file header.

    The scene only reports its own counts when it is the mesh viewer, so this is what lets a run
    of any other scene still say which mesh it was given and how big it is.

    :param name: A mesh name, alias or path.
    :return: ``(vertices, faces)``, or ``(0, 0)`` when the name resolves to nothing catalogued.
    """
    try:
        path = resolve_mesh(name)
    except ValueError:
        return 0, 0
    for mesh in discover_meshes():
        if mesh["path"] == path:
            return mesh["vertices"], mesh["faces"]
    return 0, 0


MODEL_PROPERTY = "ixdar.model"

OFF_SUFFIX = ".off"

SAVE_SUFFIX = ".save"

MODULE_RESOURCES_DIR = os.path.join(REPO_DIR, "ixdar-app", "src", "main", "resources")


def resolve_scene_properties(properties: list[str]) -> list[str]:
    """Rewrite the system properties whose values are paths a caller should not have to spell.

    A ``*.off`` or ``ixdar.model`` value that names a catalogued mesh becomes its full path;
    an ``ixdar.model`` value that names nothing is left alone, since the scene also accepts
    catalog tokens, collection directories and ``graph:`` names. A relative ``*.save`` value
    resolves against the module resources directory rather than wherever the JVM was started,
    which is what stops a save writing a stray ``src/`` tree at the checkout root.

    :param properties: ``key=value`` system properties as given on the command line.
    :return: The same list with path-valued properties resolved.
    :raises ValueError: When a ``*.off`` property names a mesh that cannot be resolved.
    """
    resolved = []
    for entry in properties:
        key, separator, value = entry.partition("=")
        if not separator or not value:
            resolved.append(entry)
        elif key.lower().endswith(OFF_SUFFIX):
            resolved.append(f"{key}={resolve_mesh(value)}")
        elif key == MODEL_PROPERTY:
            resolved.append(f"{key}={_resolve_model_value(value)}")
        elif key.lower().endswith(SAVE_SUFFIX) and not os.path.isabs(value):
            resolved.append(f"{key}={os.path.normpath(os.path.join(MODULE_RESOURCES_DIR, value))}")
        else:
            resolved.append(entry)
    return resolved


def _resolve_model_value(value: str) -> str:
    """Resolve an ``ixdar.model`` value through the mesh catalog, leaving other tokens alone.

    :param value: The property value as given.
    :return: An absolute mesh path, or ``value`` unchanged when it names no mesh file.
    """
    try:
        return resolve_mesh(value)
    except ValueError:
        return value
