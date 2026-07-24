"""Report which code a set of runs never executed, from JaCoCo exec files already on disk.

``run-scene --coverage`` prints this report for a single run. This command is for the case that
actually settles a dead-code audit: several runs merged into one picture. Coverage is per-run, and
this codebase gates whole subsystems behind system properties and keys — the fold check needs
``embeddedTMesh.foldCheck``, the failure highlight needs ``embeddedTMesh.contractFail``, the Coons
path only runs in ``quad-layout`` — so any single run's zeroes mean "not exercised here", not "dead".
Passing every run's exec file at once is what makes a zero trustworthy.

Usage:
    uv run ixdar-cli run-scene --scene quad-layout --coverage --coverage-path /tmp/quad.exec
    uv run ixdar-cli run-scene --scene embedded-tmesh --coverage --coverage-path /tmp/tmesh.exec \\
        --key "C=contracted to fixed point" --key "M=flip-surface uploaded|cannot show flips"
    uv run ixdar-cli coverage-report --exec-file /tmp/quad.exec --exec-file /tmp/tmesh.exec
"""

import os

from ..cli_registry import CliCommandResult, cli_command
from ..jacoco_coverage import DEFAULT_PACKAGE_FILTER, build_report, format_coverage

REPO_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))

IXDAR_APP_DIR = os.path.join(REPO_DIR, "ixdar-app")

CLASSES_DIR = os.path.join(IXDAR_APP_DIR, "target", "classes")

SOURCES_DIR = os.path.join(IXDAR_APP_DIR, "src", "main", "java")

DEFAULT_EXEC = os.path.join(REPO_DIR, "jacoco.exec")

REPORT_DIR = os.path.join(REPO_DIR, "target", "jacoco")


@cli_command(name="coverage-report")
def coverage_report(
    exec_file: list[str] | None = None,
    package_filter: str = DEFAULT_PACKAGE_FILTER,
    top: int = 25,
    out_dir: str = REPORT_DIR,
) -> CliCommandResult:
    """Merge JaCoCo exec files and report the code they never executed.

    :param exec_file: Repeatable path to a ``.exec`` file; several are merged (default: jacoco.exec).
    :param package_filter: Dotted package prefix the summary is restricted to.
    :param top: How many partly-covered classes to detail with their never-entered methods.
    :param out_dir: Directory for the generated XML and browsable HTML report.
    """
    exec_paths = list(exec_file or [DEFAULT_EXEC])
    missing = [path for path in exec_paths if not os.path.exists(path)]
    if missing:
        return CliCommandResult(
            payload={"error": "exec file(s) not found", "missing": missing},
            exit_code=1,
        )
    xml_path = os.path.join(out_dir, "coverage.xml")
    html_dir = os.path.join(out_dir, "html")
    os.makedirs(out_dir, exist_ok=True)
    build_report(exec_paths, CLASSES_DIR, SOURCES_DIR, xml_path, html_dir)
    report = format_coverage(xml_path, package_filter=package_filter, top=top, html_dir=html_dir)
    return CliCommandResult(payload={
        "execFiles": exec_paths,
        "xml": xml_path,
        "html": os.path.join(html_dir, "index.html"),
        "report": report.splitlines(),
    })
