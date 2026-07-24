"""Copy-paste duplication measurement over the Java sources, via PMD's CPD.

Nothing in the build measures duplication, so reuse opportunities are only ever found by noticing
them. CPD tokenizes the sources and reports every run of identical tokens that appears more than
once, which is the signal that two call sites want to be one function.

PMD is invoked through ``maven-pmd-plugin``'s ``cpd`` goal rather than by assembling a classpath of
PMD jars: the goal resolves its own dependencies, so there is nothing to fetch or keep in step. The
plugin writes ``target/cpd.xml``, which this module parses.

Ranking is by ``tokens x (occurrences - 1)`` — the tokens you would stop repeating if the clone were
factored out — so a short fragment duplicated eight times can outrank a long one duplicated twice.
"""

import os
import subprocess
import xml.etree.ElementTree as ElementTree

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))

POM_PATH = os.path.join(REPO_DIR, "ixdar-app", "pom.xml")

CPD_XML_PATH = os.path.join(REPO_DIR, "ixdar-app", "target", "cpd.xml")

PMD_PLUGIN_GOAL = "org.apache.maven.plugins:maven-pmd-plugin:3.26.0:cpd"

DEFAULT_MINIMUM_TOKENS = 100

SOURCE_ROOT_MARKER = "src/main/java/"


def run_cpd(minimum_tokens: int = DEFAULT_MINIMUM_TOKENS) -> str:
    """Run CPD over the module's Java sources and return the report path.

    :param minimum_tokens: Shortest token run treated as a clone; lower finds more and noisier.
    :return: Path to the generated ``cpd.xml``.
    :raises RuntimeError: When the PMD goal fails.
    """
    completed = subprocess.run(
        ["mvn", "-q", PMD_PLUGIN_GOAL, "-f", POM_PATH,
         f"-Dcpd.minimumTokens={minimum_tokens}", "-Dformat=xml"],
        cwd=REPO_DIR, capture_output=True, text=True,
    )
    if completed.returncode != 0:
        tail = (completed.stdout + completed.stderr).strip().splitlines()[-20:]
        raise RuntimeError("pmd:cpd failed:\n" + "\n".join(tail))
    return CPD_XML_PATH


def _local_name(tag: str) -> str:
    """Strip the XML namespace from a tag.

    :param tag: Fully-qualified element tag.
    :return: The local element name.
    """
    return tag.rpartition("}")[2]


def _relative(path: str) -> str:
    """Shorten an absolute source path to its package-qualified form.

    :param path: Absolute path to a Java file.
    :return: The path from the source root, or the input when it is not under one.
    """
    marker = path.find(SOURCE_ROOT_MARKER)
    return path[marker + len(SOURCE_ROOT_MARKER):] if marker >= 0 else path


def read_duplications(xml_path: str) -> list[dict]:
    """Parse a CPD report into clone classes.

    :param xml_path: Path to ``cpd.xml``.
    :return: One entry per clone with ``lines``, ``tokens``, ``occurrences``, ``wasted`` and
        ``sites``, sorted by ``wasted`` descending.
    """
    root = ElementTree.parse(xml_path).getroot()
    clones: list[dict] = []
    for element in root.iter():
        if _local_name(element.tag) != "duplication":
            continue
        sites = []
        for child in element:
            if _local_name(child.tag) != "file":
                continue
            sites.append({
                "path": _relative(child.get("path", "")),
                "line": int(child.get("line", 0)),
                "endLine": int(child.get("endline", 0)),
            })
        tokens = int(element.get("tokens", 0))
        clones.append({
            "lines": int(element.get("lines", 0)),
            "tokens": tokens,
            "occurrences": len(sites),
            "wasted": tokens * max(0, len(sites) - 1),
            "sites": sites,
        })
    clones.sort(key=lambda clone: -clone["wasted"])
    return clones


def format_duplications(xml_path: str, package_filter: str = "", top: int = 25) -> str:
    """Render a CPD report as a ranked clone listing.

    :param xml_path: Path to ``cpd.xml``.
    :param package_filter: Slash- or dot-separated path prefix; a clone is kept when any of its
        sites matches. Empty keeps everything.
    :param top: How many clone classes to detail.
    :return: A multi-line text report.
    """
    clones = read_duplications(xml_path)
    needle = package_filter.replace(".", "/")
    if needle:
        clones = [clone for clone in clones
                  if any(site["path"].startswith(needle) for site in clone["sites"])]
    if not clones:
        scope = f" under {package_filter!r}" if package_filter else ""
        return f"no duplication{scope} in {xml_path}"

    wasted_total = sum(clone["wasted"] for clone in clones)
    lines = [
        f"{len(clones)} clone class(es)"
        + (f" touching {package_filter}" if package_filter else "")
        + f", {wasted_total} duplicated tokens beyond the first copy",
        "",
        "ranked by tokens x (occurrences - 1) — what factoring the clone out would stop repeating:",
    ]
    for clone in clones[:top]:
        lines.append(f"  {clone['wasted']:>6}  {clone['tokens']} tokens / {clone['lines']} lines"
                     f" x {clone['occurrences']} sites")
        for site in clone["sites"]:
            lines.append(f"            {site['path']}:{site['line']}-{site['endLine']}")
    if len(clones) > top:
        lines.append(f"  ... {len(clones) - top} more clone class(es) not shown")
    return "\n".join(lines)
