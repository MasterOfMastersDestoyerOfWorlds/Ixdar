"""Numbered per-checkout run logs, so no two runs of a scene ever write the same file.

A scene's stdout used to default to one ``/tmp`` path per scene, shared by every checkout on the
machine, so an agent's run in one worktree overwrote the log of the user's F5 run in another. Logs
now live under the checkout that launched them, at ``tmp/logs/<scene>-<qualifier>-<n>.log`` with
``n`` the next free number, and ``tmp/logs/latest-<scene>.log`` always links to the newest one.
"""

import os
import re

from .automation_client import checkout_root

LOG_DIRECTORY_RELATIVE = os.path.join("tmp", "logs")

LATEST_PREFIX = "latest-"

LOG_SUFFIX = ".log"

NON_NAME_CHARACTERS = re.compile(r"[^A-Za-z0-9]+")


def name_part(text: str) -> str:
    """Reduce free text (an entry name, a mesh path) to a lowercase, hyphenated file-name part.

    :param text: Scene id, mesh name or launch entry name.
    :return: The part, or ``""`` when nothing alphanumeric is left.
    """
    return NON_NAME_CHARACTERS.sub("-", text).strip("-").lower()


def mesh_label(mesh: str, properties: list[str], model_property: str) -> str:
    """Name the mesh a run loads, for its log file name.

    ``--mesh`` wins, then the last ``<model_property>=`` system property. A path is reduced to its
    file stem, so ``meshes/fertility_in_tri.off`` and ``fertility`` both read as a mesh name.

    :param mesh: The ``--mesh`` value, or empty.
    :param properties: The ``key=value`` properties as the caller typed them.
    :param model_property: The system property that chooses the model (``ixdar.model``).
    :return: The file-name part, or ``""`` when the scene picks its own model.
    """
    chosen = mesh
    if not chosen:
        prefix = model_property + "="
        for entry in properties:
            if entry.startswith(prefix):
                chosen = entry[len(prefix):]
    stem = os.path.splitext(os.path.basename(chosen.rstrip("/")))[0] if chosen else ""
    return name_part(stem)


def next_log_path(scene: str, qualifier: str = "", root: str = "") -> str:
    """Reserve the next free numbered log for a scene in a checkout.

    The file is created exclusively, so two runs starting at the same moment in one checkout
    still get different numbers.

    :param scene: Scene id, the first part of the name and the key of the latest link.
    :param qualifier: Mesh or launch entry name; empty, or the scene id again, leaves the name as
        ``<scene>-<n>.log``.
    :param root: Checkout root; empty uses the checkout this call was made from.
    :return: Absolute path of the reserved, empty log file.
    """
    directory = os.path.join(root or checkout_root(), LOG_DIRECTORY_RELATIVE)
    os.makedirs(directory, exist_ok=True)
    scene_part, qualifier_part = name_part(scene), name_part(qualifier)
    stem = scene_part if qualifier_part in ("", scene_part) else f"{scene_part}-{qualifier_part}"
    numbered = re.compile(re.escape(stem) + r"-(\d+)" + re.escape(LOG_SUFFIX) + "$")
    taken = [int(match.group(1)) for match in map(numbered.match, os.listdir(directory)) if match]
    number = max(taken, default=0) + 1
    while True:
        path = os.path.join(directory, f"{stem}-{number}{LOG_SUFFIX}")
        try:
            with open(path, "x", encoding="utf-8"):
                return path
        except FileExistsError:
            number += 1


def point_latest(scene: str, log_path: str, root: str = "") -> str:
    """Repoint ``tmp/logs/latest-<scene>.log`` at a run's log, replacing any older link atomically.

    :param scene: Scene id the link is named for.
    :param log_path: The run's log, default or explicit.
    :param root: Checkout root; empty uses the checkout this call was made from.
    :return: Absolute path of the link.
    """
    directory = os.path.join(root or checkout_root(), LOG_DIRECTORY_RELATIVE)
    os.makedirs(directory, exist_ok=True)
    link = os.path.join(directory, f"{LATEST_PREFIX}{name_part(scene)}{LOG_SUFFIX}")
    staging = f"{link}.{os.getpid()}"
    if os.path.lexists(staging):
        os.remove(staging)
    os.symlink(os.path.relpath(os.path.abspath(log_path), directory), staging)
    os.replace(staging, link)
    return link
