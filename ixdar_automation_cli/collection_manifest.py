"""Read and write a model collection's ``collection.dsl``.

A collection is a directory of scans treated as one group: every mesh file directly inside it is a
member named by its file stem, and the manifest beside them records membership plus a keep/reject
decision per member. The format is the mesh DSL, one ``input_boolean`` keep flag and one
``load_mesh`` per member, both groups sorted by member name — the same bytes Java's
``CollectionManifest`` writes, so the CLI and the scene can hand the file back and forth.

The ``*.settings.json`` sidecars beside the scans are Trellis metadata: read here, never written.
"""

import json
import os

from .mesh_catalog import MESH_EXTENSIONS, MODEL_DIR_VARIABLE

MANIFEST_NAME = "collection.dsl"

MANIFEST_FALLBACK_DIR = "collections"

SETTINGS_SUFFIX = ".settings.json"

KEEP_PREFIX = "keep:"

KEEP_ID_PREFIX = "keep_"

MEMBER_ID_PREFIX = "member_"


def staging_root() -> str:
    """The staging directory: ``IXDAR_MODEL_DIR`` when set, else ``~/.ix/ixdar-models``.

    :return: Absolute path to the staging directory.
    """
    override = os.environ.get(MODEL_DIR_VARIABLE, "").strip()
    if override:
        return os.path.abspath(os.path.expanduser(override))
    return os.path.join(os.path.expanduser("~"), ".ix", "ixdar-models")


def manifest_path(directory: str) -> str:
    """Where a scan directory's manifest belongs.

    Beside the scans when that directory can be written, otherwise under the staging root so a
    read-only corpus still records decisions.

    :param directory: Directory of scans.
    :return: Absolute path of that directory's manifest file.
    """
    absolute = os.path.abspath(os.path.expanduser(directory))
    if os.access(absolute, os.W_OK):
        return os.path.join(absolute, MANIFEST_NAME)
    return os.path.join(staging_root(), MANIFEST_FALLBACK_DIR, os.path.basename(absolute),
                        MANIFEST_NAME)


def _identifier(member_name: str) -> str:
    """Fold a member name into the characters a DSL identifier allows.

    :param member_name: File stem naming the member.
    :return: The folded identifier text.
    """
    return "".join(character if character.isalnum() or character == "_" else "_"
                   for character in member_name)


def keep_statement_id(member_name: str) -> str:
    """Statement id of a member's keep flag.

    :param member_name: File stem naming the member.
    :return: A DSL identifier unique to that member's flag.
    """
    return KEEP_ID_PREFIX + _identifier(member_name)


def member_statement_id(member_name: str) -> str:
    """Statement id of a member's ``load_mesh``.

    :param member_name: File stem naming the member.
    :return: A DSL identifier for that member.
    """
    identifier = _identifier(member_name)
    if not identifier or identifier[0].isdigit():
        return MEMBER_ID_PREFIX + identifier
    return identifier


def read_settings(member_path: str) -> dict:
    """Read the ``*.settings.json`` sidecar beside a member's mesh file.

    :param member_path: Path to the member's mesh file.
    :return: The sidecar's key/value pairs, or ``{}`` when absent or unreadable.
    """
    stem, _extension = os.path.splitext(member_path)
    sidecar = stem + SETTINGS_SUFFIX
    try:
        with open(sidecar, encoding="utf-8") as handle:
            document = json.load(handle)
    except (OSError, ValueError):
        return {}
    return {key: value for key, value in document.items() if not isinstance(value, (dict, list))}


def settings_summary(settings: dict) -> str:
    """One-line summary of a member's settings: every scalar as ``key=value``, keys sorted.

    Sorted so the text is stable whatever order the sidecar listed its keys in, and so it matches
    what ``ModelCollection.settingsSummary`` prints in the scene menu and in ui-state.

    :param settings: Sidecar key/value pairs.
    :return: ``key=value`` pairs separated by spaces, empty when there are no settings.
    """
    return " ".join(f"{key}={_scalar_text(settings[key])}" for key in sorted(settings))


def _scalar_text(value) -> str:
    """Render one sidecar value the way the Java side spells it.

    :param value: A JSON scalar from the sidecar.
    :return: The value as text; booleans lower-case, integral floats without a trailing ``.0``.
    """
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def read_keep_flags(manifest: str) -> dict[str, bool]:
    """Keep flags a manifest records, keyed by member name.

    :param manifest: Path to a manifest, which need not exist.
    :return: Member name to keep flag; empty when the manifest is absent or unreadable.
    """
    flags: dict[str, bool] = {}
    try:
        with open(manifest, encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return flags
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith(KEEP_ID_PREFIX) or "input_boolean(" not in stripped:
            continue
        name = _quoted_argument(stripped, "name=")
        if name is None or not name.startswith(KEEP_PREFIX):
            continue
        flags[name[len(KEEP_PREFIX):]] = "default=true" in stripped.replace(" ", "")
    return flags


def _quoted_argument(statement: str, prefix: str) -> str | None:
    """Read a quoted argument value out of one manifest statement.

    :param statement: A single ``id = node(...)`` line.
    :param prefix: The argument prefix to find, e.g. ``name=``.
    :return: The unquoted value, or ``None`` when the argument is absent.
    """
    start = statement.find(prefix + '"')
    if start < 0:
        return None
    start += len(prefix) + 1
    end = statement.find('"', start)
    if end < 0:
        return None
    return statement[start:end]


def read_manifest(manifest: str) -> dict:
    """Read a manifest back into a collection.

    :param manifest: Path to a manifest written by :func:`write_manifest`.
    :return: A collection dict with ``name``, ``directory``, ``manifest`` and ``members``.
    :raises OSError: When the manifest cannot be read.
    """
    with open(manifest, encoding="utf-8") as handle:
        text = handle.read()
    flags = read_keep_flags(manifest)
    members = []
    directory = ""
    for line in text.splitlines():
        stripped = line.strip()
        if "load_mesh(" not in stripped:
            continue
        path = _quoted_argument(stripped, "path=")
        if not path:
            continue
        if not directory:
            directory = os.path.dirname(path)
        name = os.path.splitext(os.path.basename(path))[0]
        members.append({
            "name": name,
            "path": path,
            "keep": flags.get(name, True),
            "settings": read_settings(path),
        })
    members.sort(key=lambda member: member["name"])
    absolute = os.path.abspath(manifest)
    holder = os.path.dirname(absolute)
    return {
        "name": os.path.basename(holder),
        "directory": directory or holder,
        "manifest": absolute,
        "members": members,
    }


def scan_directory(directory: str) -> dict:
    """Scan a directory of scans into a collection, taking keep flags from its manifest.

    :param directory: Directory of scans.
    :return: A collection dict with ``name``, ``directory``, ``manifest`` and ``members``.
    :raises ValueError: When the directory does not exist.
    """
    absolute = os.path.abspath(os.path.expanduser(directory))
    if not os.path.isdir(absolute):
        raise ValueError(f"not a directory: {directory}")
    manifest = manifest_path(absolute)
    flags = read_keep_flags(manifest)
    members = []
    for filename in sorted(os.listdir(absolute)):
        path = os.path.join(absolute, filename)
        stem, extension = os.path.splitext(filename)
        if not os.path.isfile(path) or extension.lower() not in MESH_EXTENSIONS:
            continue
        members.append({
            "name": stem,
            "path": path,
            "keep": flags.get(stem, True),
            "settings": read_settings(path),
        })
    members.sort(key=lambda member: member["name"])
    return {
        "name": os.path.basename(absolute),
        "directory": absolute,
        "manifest": manifest,
        "members": members,
    }


def render(collection: dict) -> str:
    """Render a collection's manifest text, byte for byte as the scene writes it.

    :param collection: Collection dict from :func:`scan_directory` or :func:`read_manifest`.
    :return: The manifest source text, ending in a newline.
    """
    members = sorted(collection["members"], key=lambda member: member["name"])
    kept = sum(1 for member in members if member["keep"])
    lines = [
        f'# Ixdar model collection "{collection["name"]}" - {len(members)} members, {kept} kept',
        f"# Scans in {collection['directory']}",
        "# Membership and keep/reject decisions only; members are merged nowhere yet.",
        "",
    ]
    for member in members:
        default = "true" if member["keep"] else "false"
        lines.append(f'{keep_statement_id(member["name"])} = input_boolean('
                     f'name="{KEEP_PREFIX}{member["name"]}", default={default})')
    lines.append("")
    for member in members:
        lines.append(f'{member_statement_id(member["name"])} = load_mesh('
                     f'path="{member["path"]}")')
    return "\n".join(lines) + "\n"


def write_manifest(collection: dict) -> str:
    """Write a collection's manifest, creating the directory it lives in.

    :param collection: Collection dict whose ``manifest`` names the file to write.
    :return: The path written.
    :raises OSError: When the manifest cannot be written.
    """
    manifest = collection["manifest"]
    os.makedirs(os.path.dirname(manifest), exist_ok=True)
    with open(manifest, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(render(collection))
    return manifest


def set_keep(directory: str, member_name: str, keep: bool) -> dict:
    """Set one member's keep flag and rewrite the manifest.

    :param directory: Directory of scans.
    :param member_name: File stem naming the member.
    :param keep: ``True`` to keep the member, ``False`` to reject it.
    :return: The rescanned collection with the new flag applied.
    :raises ValueError: When the directory has no member of that name.
    """
    collection = scan_directory(directory)
    for member in collection["members"]:
        if member["name"] == member_name:
            member["keep"] = keep
            write_manifest(collection)
            return collection
    known = ", ".join(member["name"] for member in collection["members"])
    raise ValueError(f"no member {member_name!r} in {collection['name']}; members: {known}")


def collection_roots() -> list[str]:
    """Directories searched for collections: the mesh roots and the staging fallback area.

    :return: Existing directories to search, without duplicates.
    """
    from .mesh_catalog import mesh_roots

    roots = list(mesh_roots())
    fallback = os.path.join(staging_root(), MANIFEST_FALLBACK_DIR)
    if os.path.isdir(fallback) and fallback not in roots:
        roots.append(fallback)
    return roots


def discover_collections() -> list[dict]:
    """Find every collection that has a manifest under the searched roots.

    :return: One collection dict per manifest, sorted by collection name.
    """
    found: dict[str, dict] = {}
    for root in collection_roots():
        for directory, _subdirectories, files in os.walk(root):
            if MANIFEST_NAME not in files:
                continue
            manifest = os.path.join(directory, MANIFEST_NAME)
            try:
                collection = read_manifest(manifest)
            except OSError:
                continue
            scan_dir = collection["directory"]
            if os.path.isdir(scan_dir):
                collection = scan_directory(scan_dir)
            found[collection["manifest"]] = collection
    return sorted(found.values(), key=lambda entry: entry["name"])
