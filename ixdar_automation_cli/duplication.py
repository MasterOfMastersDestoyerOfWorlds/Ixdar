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

import html
import os
import subprocess
import xml.etree.ElementTree as ElementTree

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))

POM_PATH = os.path.join(REPO_DIR, "ixdar-app", "pom.xml")

CPD_XML_PATH = os.path.join(REPO_DIR, "ixdar-app", "target", "cpd.xml")

CPD_HTML_PATH = os.path.join(REPO_DIR, "ixdar-app", "target", "cpd-report.html")

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

    Each site keeps the absolute ``fullPath`` alongside the shortened ``path``, so the report can
    link a site back to the editor. The ``<codefragment>`` CDATA, when CPD emits it, is the actual
    duplicated source and is carried on the clone as ``fragment``.

    :param xml_path: Path to ``cpd.xml``.
    :return: One entry per clone with ``lines``, ``tokens``, ``occurrences``, ``wasted``, ``sites``
        and ``fragment``, sorted by ``wasted`` descending.
    """
    root = ElementTree.parse(xml_path).getroot()
    clones: list[dict] = []
    for element in root.iter():
        if _local_name(element.tag) != "duplication":
            continue
        sites = []
        fragment = ""
        for child in element:
            if _local_name(child.tag) == "file":
                full_path = child.get("path", "")
                sites.append({
                    "fullPath": full_path,
                    "path": _relative(full_path),
                    "line": int(child.get("line", 0)),
                    "endLine": int(child.get("endline", 0)),
                })
            elif _local_name(child.tag) == "codefragment":
                fragment = child.text or ""
        tokens = int(element.get("tokens", 0))
        clones.append({
            "lines": int(element.get("lines", 0)),
            "tokens": tokens,
            "occurrences": len(sites),
            "wasted": tokens * max(0, len(sites) - 1),
            "sites": sites,
            "fragment": fragment,
        })
    clones.sort(key=lambda clone: -clone["wasted"])
    return clones


def filter_clones(clones: list[dict], package_filter: str) -> list[dict]:
    """Keep clones with at least one site under a package prefix.

    :param clones: Clone classes from :func:`read_duplications`.
    :param package_filter: Slash- or dot-separated prefix; empty keeps everything.
    :return: The filtered clones, in the input order.
    """
    needle = package_filter.replace(".", "/")
    if not needle:
        return clones
    return [clone for clone in clones
            if any(site["path"].startswith(needle) for site in clone["sites"])]


def format_duplications(xml_path: str, package_filter: str = "", top: int = 25) -> str:
    """Render a CPD report as a ranked clone listing.

    :param xml_path: Path to ``cpd.xml``.
    :param package_filter: Slash- or dot-separated path prefix; a clone is kept when any of its
        sites matches. Empty keeps everything.
    :param top: How many clone classes to detail.
    :return: A multi-line text report.
    """
    clones = filter_clones(read_duplications(xml_path), package_filter)
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


def _site_link(site: dict) -> str:
    """Render one clone site as a list item linking into VS Code at the start line.

    The ``vscode://file/<absolute-path>:<line>`` scheme is what makes a site openable — a relative
    path would not resolve, so the absolute ``fullPath`` from the report is used for the href while
    the shortened ``path`` is shown.

    :param site: A site dict from :func:`read_duplications`.
    :return: An HTML ``<li>`` with the link.
    """
    href = "vscode://file" + html.escape(site["fullPath"]) + ":" + str(site["line"])
    return (f'<li><a href="{href}"><span class="path">{html.escape(site["path"])}</span>'
            f'<span class="lines">:{site["line"]}–{site["endLine"]}</span></a></li>')


def write_html_report(xml_path: str, html_path: str, package_filter: str = "") -> str:
    """Render a CPD report as a self-contained, theme-aware HTML page.

    Unlike PMD's own site HTML — which only the ``mvn site`` lifecycle produces and which has no
    stable structure — this is generated from the same schema-backed XML the text report parses, so
    the two never disagree. The page inlines its CSS and needs no server.

    :param xml_path: Path to ``cpd.xml``.
    :param html_path: Path the HTML page is written to.
    :param package_filter: Slash- or dot-separated prefix restricting the clones shown.
    :return: ``html_path``.
    """
    clones = filter_clones(read_duplications(xml_path), package_filter)
    wasted_total = sum(clone["wasted"] for clone in clones)
    scope = html.escape(package_filter) if package_filter else "all source"

    cards = []
    for rank, clone in enumerate(clones, start=1):
        sites = "".join(_site_link(site) for site in clone["sites"])
        fragment = ""
        if clone["fragment"].strip():
            fragment = (f'<details><summary>duplicated code</summary>'
                        f'<pre>{html.escape(clone["fragment"].strip(chr(10)))}</pre></details>')
        cards.append(
            f'<article class="clone">'
            f'<header><span class="rank">#{rank}</span>'
            f'<span class="wasted" title="tokens x (occurrences - 1)">{clone["wasted"]}</span>'
            f'<span class="meta">{clone["tokens"]} tokens &middot; {clone["lines"]} lines'
            f' &middot; {clone["occurrences"]} sites</span></header>'
            f'<ul class="sites">{sites}</ul>{fragment}</article>')

    body = ("<p class=\"empty\">No duplication found for this scope.</p>"
            if not clones else "".join(cards))

    document = _HTML_TEMPLATE.format(
        scope=scope,
        clone_count=len(clones),
        wasted_total=wasted_total,
        cards=body,
    )
    with open(html_path, "w", encoding="utf-8") as handle:
        handle.write(document)
    return html_path


_HTML_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Duplication report</title>
<style>
  :root {{
    color-scheme: light dark;
    --bg: #ffffff; --fg: #1a1a1a; --muted: #666; --card: #f6f7f9;
    --border: #e2e4e8; --accent: #b5462f; --path: #2a5db0;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg: #16181d; --fg: #e6e6e6; --muted: #9aa0a8; --card: #1e2128;
      --border: #2c313a; --accent: #e08a6f; --path: #7aa2e3;
    }}
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 2rem 1.25rem; background: var(--bg); color: var(--fg);
    font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  }}
  main {{ max-width: 60rem; margin: 0 auto; }}
  h1 {{ font-size: 1.4rem; margin: 0 0 .25rem; }}
  .summary {{ color: var(--muted); margin: 0 0 1.5rem; }}
  .summary strong {{ color: var(--fg); }}
  .clone {{
    background: var(--card); border: 1px solid var(--border); border-radius: 8px;
    padding: .85rem 1rem; margin-bottom: .75rem;
  }}
  .clone header {{ display: flex; align-items: baseline; gap: .75rem; flex-wrap: wrap; }}
  .rank {{ color: var(--muted); font-variant-numeric: tabular-nums; min-width: 2.2rem; }}
  .wasted {{
    font-weight: 700; font-size: 1.15rem; color: var(--accent);
    font-variant-numeric: tabular-nums;
  }}
  .meta {{ color: var(--muted); font-size: .9rem; }}
  ul.sites {{ margin: .6rem 0 0; padding: 0; list-style: none; }}
  ul.sites li {{
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .85rem;
    padding: .12rem 0; overflow-x: auto; white-space: nowrap;
  }}
  ul.sites a {{ text-decoration: none; }}
  ul.sites a:hover .path {{ text-decoration: underline; }}
  .path {{ color: var(--path); }}
  .lines {{ color: var(--muted); }}
  .empty {{ color: var(--muted); }}
  details {{ margin-top: .6rem; }}
  summary {{
    cursor: pointer; color: var(--muted); font-size: .82rem; user-select: none;
  }}
  details pre {{
    margin: .5rem 0 0; padding: .7rem .85rem; background: var(--bg);
    border: 1px solid var(--border); border-radius: 6px; overflow-x: auto;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .82rem;
    line-height: 1.45;
  }}
  footer {{ margin-top: 2rem; color: var(--muted); font-size: .8rem; }}
</style>
</head>
<body>
<main>
<h1>Duplication report</h1>
<p class="summary"><strong>{clone_count}</strong> clone class(es) in {scope} &middot;
<strong>{wasted_total}</strong> duplicated tokens beyond the first copy &middot;
ranked by tokens &times; (occurrences &minus; 1)</p>
{cards}
<footer>Generated from cpd.xml by ixdar-cli duplication-report.</footer>
</main>
</body>
</html>
"""
