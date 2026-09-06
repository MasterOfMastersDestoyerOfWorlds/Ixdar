"""Browse a model collection and record keep/reject decisions from the command line.

A collection is a directory of scans plus the ``collection.dsl`` manifest beside them. These
commands read and rewrite that manifest without launching the app, so a member can be rejected from
a script the same way ``K`` rejects it in the scene's COLLECTION menu.

Usage:
    uv run ixdar-cli collection-list
    uv run ixdar-cli collection-list --directory ~/crawfish
    uv run ixdar-cli collection-keep --directory ~/crawfish --member IMG_4109
    uv run ixdar-cli collection-reject --directory ~/crawfish --member IMG_4109
"""

from ..cli_registry import CliCommandResult, cli_command
from ..collection_manifest import (
    discover_collections,
    scan_directory,
    set_keep,
    settings_summary,
)


def _listing(collection: dict) -> list[str]:
    """Render one collection as printable rows.

    :param collection: Collection dict from the manifest module.
    :return: A header row followed by one row per member.
    """
    kept = sum(1 for member in collection["members"] if member["keep"])
    rows = [f"{collection['name']}  {len(collection['members'])} members, {kept} kept  "
            f"{collection['manifest']}"]
    for member in collection["members"]:
        flag = "[x]" if member["keep"] else "[ ]"
        summary = settings_summary(member["settings"])
        rows.append(f"  {flag} {member['name']:<16} {member['path']}"
                    + (f"  {summary}" if summary else ""))
    return rows


@cli_command(name="collection-list")
def collection_list(directory: str = "") -> CliCommandResult:
    """List model collections and their members with keep flags.

    With no directory, every collection that has a manifest under the mesh roots is listed.

    :param directory: Scan directory to read; omit to discover collections from the mesh roots.
    """
    if directory:
        collections = [scan_directory(directory)]
    else:
        collections = discover_collections()
    listing: list[str] = []
    for collection in collections:
        listing.extend(_listing(collection))
    return CliCommandResult(payload={
        "count": len(collections),
        "collections": collections,
        "listing": listing,
    })


@cli_command(name="collection-keep")
def collection_keep(directory: str, member: str) -> CliCommandResult:
    """Mark one collection member as kept and rewrite its manifest.

    :param directory: Scan directory holding the member.
    :param member: Member name, i.e. the mesh file's stem.
    """
    collection = set_keep(directory, member, True)
    return CliCommandResult(payload={
        "member": member,
        "keep": True,
        "manifest": collection["manifest"],
        "listing": _listing(collection),
    })


@cli_command(name="collection-reject")
def collection_reject(directory: str, member: str) -> CliCommandResult:
    """Mark one collection member as rejected and rewrite its manifest.

    :param directory: Scan directory holding the member.
    :param member: Member name, i.e. the mesh file's stem.
    """
    collection = set_keep(directory, member, False)
    return CliCommandResult(payload={
        "member": member,
        "keep": False,
        "manifest": collection["manifest"],
        "listing": _listing(collection),
    })
