"""Reading a stroke-sculpt DSL from the CLI side: its node lines, their arguments, and the files they name.

Shared by the sculpt commands (sculpt-animate, sculpt-snapshot, sculpt-migrate, stroke-report) so each
reads a DSL line and resolves a ``svg=`` or ``path=`` argument exactly as the Java nodes do
(``MeshLoader.resolve``: as given, under ``ixdar-app``, under ``src/main/resources`` with or without the
module).
"""

import os
import re

NODE_LINE = re.compile(r"^\s*([A-Za-z_]\w*)\s*=\s*(\w+)\s*\((.*)\)\s*$")
ARGUMENT = re.compile(r"(\w+)\s*=\s*(\"(?:[^\"\\]|\\.)*\"|[^,()]+)")
FILE_ARGUMENTS = {"stroke_sculpt": "svg", "load_mesh": "path", "silhouette_hull": "svg"}
RESOURCE_DIRECTORY = os.path.join("src", "main", "resources")
MODULE_DIRECTORY = "ixdar-app"


def resolve_resource(path: str, root: str) -> str:
    """The file a node's path argument names, resolved the way ``MeshLoader.resolve`` does.

    :param path: the argument as written in the DSL
    :param root: checkout root, the Java side's working directory
    :return: the absolute path of the first candidate that exists
    :raises FileNotFoundError: when none does
    """
    for candidate in (path, os.path.join(MODULE_DIRECTORY, path), os.path.join(RESOURCE_DIRECTORY, path),
                      os.path.join(MODULE_DIRECTORY, RESOURCE_DIRECTORY, path)):
        full = candidate if os.path.isabs(candidate) else os.path.join(root, candidate)
        if os.path.isfile(full):
            return os.path.abspath(full)
    raise FileNotFoundError(f"no file at {path} (tried it as given, under ixdar-app and under src/main/resources)")


def dsl_nodes(lines: list[str]) -> list[dict]:
    """Every node statement of a DSL: its line, id, type and arguments as written.

    :param lines: the DSL's lines
    :return: one dict per statement with ``line``, ``id``, ``type`` and ``arguments`` (name to raw text,
             quotes kept)
    """
    nodes = []
    for index, line in enumerate(lines):
        match = NODE_LINE.match(line)
        if match:
            nodes.append({"line": index, "id": match.group(1), "type": match.group(2),
                          "arguments": {name: value.strip() for name, value in ARGUMENT.findall(match.group(3))}})
    return nodes


def unquoted(value: str) -> str:
    """An argument's text without its surrounding quotes.

    :param value: the raw argument
    :return: the string inside the quotes, or the value itself when unquoted
    """
    return value[1:-1] if len(value) >= 2 and value[0] == value[-1] == '"' else value
