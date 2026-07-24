"""Line and method coverage for a scene run, via the JaCoCo agent.

async-profiler answers "where was the CPU"; it samples, so code that runs once and returns in
microseconds never appears. This module answers the different question "did this execute at all",
which is what an audit for dead code needs. JaCoCo instruments every basic block, so a method that
ran once is recorded exactly like one that ran a million times.

The agent writes its ``.exec`` on the JVM shutdown hook, so — exactly like async-profiler's HTML —
a run only produces a usable file if the JVM exits cleanly. ``run-scene`` already shuts the scene
down through the automation server, so both work for the same reason.

Coverage is per-run and this codebase hides whole subsystems behind system properties and keys, so
one run's zeroes mean "not exercised by this configuration", not "dead". ``jacococli report`` takes
several exec files and merges them, which is why every entry point here accepts a list.
"""

import glob
import os
import subprocess
import xml.etree.ElementTree as ElementTree

M2_JACOCO_DIR = os.path.expanduser("~/.m2/repository/org/jacoco")

AGENT_ARTIFACT_GLOB = os.path.join(
    M2_JACOCO_DIR, "org.jacoco.agent", "*", "org.jacoco.agent-*-runtime.jar")

CLI_ARTIFACT_GLOB = os.path.join(
    M2_JACOCO_DIR, "org.jacoco.cli", "*", "org.jacoco.cli-*-nodeps.jar")

FETCH_HINT = (
    "mvn dependency:get -Dartifact=org.jacoco:org.jacoco.agent:0.8.13:jar:runtime && "
    "mvn dependency:get -Dartifact=org.jacoco:org.jacoco.cli:0.8.13:jar:nodeps")

DEFAULT_INCLUDES = "ixdar.*"

DEFAULT_PACKAGE_FILTER = "ixdar.geometry.mesh.quadlayout"

LINE_COUNTER = "LINE"


def _newest_jar(pattern: str, artifact: str) -> str:
    """Resolve the highest-versioned local copy of a JaCoCo jar.

    :param pattern: Glob over the local Maven repository.
    :param artifact: Artifact name, for the error message.
    :return: Absolute path to the jar.
    :raises RuntimeError: When no copy is in the local repository.
    """
    matches = sorted(glob.glob(pattern))
    if not matches:
        raise RuntimeError(
            f"JaCoCo {artifact} jar not found under {M2_JACOCO_DIR}. Fetch it with:\n  {FETCH_HINT}")
    return matches[-1]


def agent_jar() -> str:
    """Path to the JaCoCo agent jar, which is attached with ``-javaagent``.

    :return: Absolute path to the agent jar.
    """
    return _newest_jar(AGENT_ARTIFACT_GLOB, "agent")


def cli_jar() -> str:
    """Path to the ``jacococli`` jar, which turns exec files into reports.

    :return: Absolute path to the CLI jar.
    """
    return _newest_jar(CLI_ARTIFACT_GLOB, "cli")


def agent_argument(destination: str, includes: str = DEFAULT_INCLUDES) -> str:
    """Build the ``-javaagent`` argument that records coverage for a scene run.

    ``append=false`` makes each run start from zero rather than silently unioning with whatever an
    earlier run left at the same path — merging is done deliberately at report time instead.

    :param destination: Path the ``.exec`` is written to at JVM exit.
    :param includes: Class-name pattern to instrument; narrow, since instrumenting the world is slow.
    :return: The full ``-javaagent:...`` argument.
    """
    return (f"-javaagent:{agent_jar()}=destfile={destination}"
            f",includes={includes},append=false")


def build_report(exec_paths: list[str], classes_dir: str, sources_dir: str,
                 xml_path: str, html_dir: str) -> None:
    """Run ``jacococli report`` over one or more exec files, merging them.

    :param exec_paths: Exec files to read; several are merged into one report.
    :param classes_dir: Compiled classes the exec ids are resolved against.
    :param sources_dir: Java sources, so the HTML can highlight individual lines.
    :param xml_path: Output path for the XML report, which the text summary parses.
    :param html_dir: Output directory for the browsable line-by-line HTML report.
    :raises RuntimeError: When jacococli fails.
    """
    command = [
        "java", "-jar", cli_jar(), "report", *exec_paths,
        "--classfiles", classes_dir,
        "--sourcefiles", sources_dir,
        "--xml", xml_path,
        "--html", html_dir,
        "--quiet",
    ]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        tail = (completed.stdout + completed.stderr).strip().splitlines()[-15:]
        raise RuntimeError("jacococli report failed:\n" + "\n".join(tail))


def _counter(element: ElementTree.Element, counter_type: str) -> tuple[int, int]:
    """Read a JaCoCo counter off a report element.

    :param element: A ``package``, ``class`` or ``method`` element.
    :param counter_type: Counter name, e.g. ``LINE``.
    :return: ``(missed, covered)``, both zero when the counter is absent.
    """
    for counter in element.findall("counter"):
        if counter.get("type") == counter_type:
            return int(counter.get("missed", 0)), int(counter.get("covered", 0))
    return 0, 0


def read_coverage(xml_path: str, package_filter: str = "") -> dict:
    """Parse a JaCoCo XML report into per-class and per-method line coverage.

    :param xml_path: Path to the XML report written by :func:`build_report`.
    :param package_filter: Dotted package prefix to restrict the result to; empty keeps everything.
    :return: ``{"classes": [...], "totalMissed": int, "totalCovered": int}`` where each class holds
        its methods, each with ``missed``/``covered`` line counts.
    """
    root = ElementTree.parse(xml_path).getroot()
    prefix = package_filter.replace(".", "/")
    classes: list[dict] = []
    total_missed = 0
    total_covered = 0
    for package in root.findall("package"):
        package_name = package.get("name", "")
        if prefix and not package_name.startswith(prefix):
            continue
        for class_element in package.findall("class"):
            class_missed, class_covered = _counter(class_element, LINE_COUNTER)
            total_missed += class_missed
            total_covered += class_covered
            methods = []
            for method in class_element.findall("method"):
                method_missed, method_covered = _counter(method, LINE_COUNTER)
                methods.append({
                    "name": method.get("name", ""),
                    "line": int(method.get("line", 0)),
                    "missed": method_missed,
                    "covered": method_covered,
                })
            classes.append({
                "name": (class_element.get("name", "") or "").replace("/", "."),
                "sourceFile": class_element.get("sourcefilename", ""),
                "missed": class_missed,
                "covered": class_covered,
                "methods": methods,
            })
    return {"classes": classes, "totalMissed": total_missed, "totalCovered": total_covered}


def format_coverage(xml_path: str, package_filter: str = DEFAULT_PACKAGE_FILTER,
                    top: int = 25, html_dir: str = "") -> str:
    """Summarize a coverage report as the delete-candidate list an audit wants.

    Classes with zero covered lines are listed first and whole, since those are the file-level
    deletions. Partly-covered classes then get their never-entered methods listed, which is where
    diagnostic code hiding inside a live class shows up.

    :param xml_path: Path to the XML report.
    :param package_filter: Dotted package prefix to restrict the report to.
    :param top: How many partly-covered classes to detail.
    :param html_dir: HTML report directory to point at for line-by-line detail, if one was written.
    :return: A multi-line text report.
    """
    coverage = read_coverage(xml_path, package_filter)
    classes = coverage["classes"]
    if not classes:
        return f"no classes matched {package_filter!r} in {xml_path}"

    covered_total = coverage["totalCovered"]
    missed_total = coverage["totalMissed"]
    line_total = covered_total + missed_total
    percent = (100.0 * covered_total / line_total) if line_total else 0.0
    lines = [
        f"coverage of {package_filter}: {covered_total}/{line_total} lines ({percent:.1f}%),"
        f" {missed_total} never executed, {len(classes)} classes",
        "",
    ]

    untouched = sorted((entry for entry in classes if entry["covered"] == 0),
                       key=lambda entry: -entry["missed"])
    lines.append(f"never entered — {len(untouched)} class(es), "
                 f"{sum(entry['missed'] for entry in untouched)} lines:")
    for entry in untouched:
        lines.append(f"  {entry['missed']:6d}  {entry['name']}")
    if not untouched:
        lines.append("  (none)")
    lines.append("")

    partial = sorted((entry for entry in classes if entry["covered"] > 0 and entry["missed"] > 0),
                     key=lambda entry: -entry["missed"])
    lines.append(f"partly covered — never-executed methods in the top {top} by missed lines:")
    for entry in partial[:top]:
        entry_total = entry["covered"] + entry["missed"]
        lines.append(f"  {entry['name']}  {entry['covered']}/{entry_total} lines")
        dead_methods = sorted((method for method in entry["methods"] if method["covered"] == 0),
                              key=lambda method: -method["missed"])
        for method in dead_methods:
            if method["missed"] == 0:
                continue
            lines.append(f"      {method['missed']:5d}  {method['name']}  (line {method['line']})")
        if not dead_methods:
            lines.append("      (every method entered; misses are inside covered methods)")
    if not partial:
        lines.append("  (none)")

    if html_dir:
        lines.extend(["", f"line-by-line detail: {os.path.join(html_dir, 'index.html')}"])
    return "\n".join(lines)
