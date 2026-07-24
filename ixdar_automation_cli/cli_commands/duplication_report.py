"""Find copy-paste duplication in the Java sources, the reuse-opportunity counterpart to coverage.

``coverage-report`` says which code never runs; this says which code exists more than once. Together
they cover the two ways a file gets bigger than it needs to be.

Runs PMD's CPD over ``ixdar-app/src/main/java`` and ranks clone classes by how many tokens factoring
each one out would stop repeating, so the top of the list is where reuse pays best.

Usage:
    uv run ixdar-cli duplication-report
    uv run ixdar-cli duplication-report --min-tokens 60 --package-filter ixdar.geometry.mesh
    uv run ixdar-cli duplication-report --xml ixdar-app/target/cpd.xml
"""

import os

from ..cli_registry import CliCommandResult, cli_command
from ..duplication import (CPD_HTML_PATH, DEFAULT_MINIMUM_TOKENS, filter_clones,
                           format_duplications, read_duplications, run_cpd, write_html_report)


@cli_command(name="duplication-report")
def duplication_report(
    min_tokens: int = DEFAULT_MINIMUM_TOKENS,
    package_filter: str = "",
    top: int = 25,
    html: str = "",
    xml: str = "",
) -> CliCommandResult:
    """Report duplicated code ranked by how much repetition factoring it out would remove.

    :param min_tokens: Shortest token run counted as a clone; lower finds more and noisier matches.
    :param package_filter: Dotted or slashed package prefix; keeps clones with a site under it.
    :param top: How many clone classes to detail in the text report; the HTML lists them all.
    :param html: Path for the browsable HTML report; defaults to target/cpd-report.html.
    :param xml: Reuse an existing CPD report instead of re-running PMD.
    """
    if xml:
        if not os.path.exists(xml):
            return CliCommandResult(
                payload={"error": "CPD report not found", "xml": xml}, exit_code=1)
        xml_path = xml
    else:
        xml_path = run_cpd(min_tokens)

    clones = filter_clones(read_duplications(xml_path), package_filter)
    report = format_duplications(xml_path, package_filter=package_filter, top=top)
    html_path = write_html_report(xml_path, html or CPD_HTML_PATH, package_filter=package_filter)
    return CliCommandResult(payload={
        "xml": xml_path,
        "html": html_path,
        "minimumTokens": min_tokens,
        "cloneClasses": len(clones),
        "duplicatedTokens": sum(clone["wasted"] for clone in clones),
        "report": report.splitlines(),
    })
