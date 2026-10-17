#!/usr/bin/env python3
"""List every colour written outside Color.java, with file:line and kind, and count them by kind.

Java is audited by autofix's ColorLiteralCheck, the same logic the build enforces, configured with
no construction allowance so derived colours in the color package are listed too. Packed RGB (a
hex int, a channel shift or an int named as a colour) is listed in every file, Color.java
included. Shaders, SVGs
and DSL models are not Java, so this script scans them itself:

- shader: a vec2/vec3/vec4 of literals in a GLSL function whose name says colour, or assigned to a
  variable that says colour or to FragColor, in resources/glsl; and a FragColor assigned a
  literal vector in a shader string Java builds;
- asset: a #RGB/#RRGGBB colour in an SVG under resources, or a ``color=<r, g, b>`` in a DSL model.

    uv run python tools/colour_audit.py                    # autofix-tool from ~/.m2
    uv run python tools/colour_audit.py --autofix-classes ~/Code/autofix/target/classes

``--autofix-classes`` puts an uninstalled autofix build first on the classpath, so a change to the
check can be audited before it is installed.
"""

import argparse
import collections
import pathlib
import re
import subprocess
import sys
import tempfile

REPOSITORY = pathlib.Path(__file__).resolve().parent.parent

APP = REPOSITORY / "ixdar-app"

JAVA_ROOTS = [APP / "src" / "main" / "java", REPOSITORY / "annotations" / "src" / "main" / "java"]

RESOURCES = APP / "src" / "main" / "resources"

COLOR_JAVA = r"[\\/]ixdar[\\/]graphics[\\/]render[\\/]color[\\/]Color\.java$"

AUDIT_CONFIGURATION = f"""<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="severity" value="warning"/>
  <module name="TreeWalker">
    <module name="ixdar.autofix.checkstyle.ColorLiteralCheck">
      <property name="literalFiles" value="{COLOR_JAVA}"/>
      <property name="constructionFiles" value="{COLOR_JAVA}"/>
    </module>
  </module>
</module>
"""

CLASSPATH_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ixdar.tools</groupId>
  <artifactId>colour-audit-classpath</artifactId>
  <version>0.0.1</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>com.puppycrawl.tools</groupId>
      <artifactId>checkstyle</artifactId>
      <version>{checkstyle}</version>
    </dependency>
    <dependency>
      <groupId>IXDAR</groupId>
      <artifactId>autofix-tool</artifactId>
      <version>{autofix}</version>
    </dependency>
  </dependencies>
</project>
"""

CHECKSTYLE_VIOLATION = re.compile(
    r"^\[WARN\]\s+(?P<path>.+?):(?P<line>\d+)(?::\d+)?:\s+Colou?r [^(]*\((?P<kind>[^)]+)\)"
)

LITERAL_VECTOR = re.compile(
    r"\bvec[234]\s*\(\s*-?[\d.]+(?:[eE]-?\d+)?\s*(?:,\s*-?[\d.]+(?:[eE]-?\d+)?\s*)*\)"
)

GLSL_FUNCTION = re.compile(r"^\s*(?:\w+\s+)+(?P<name>\w+)\s*\([^;]*$")

ASSIGNMENT_TARGET = re.compile(r"(?P<name>[A-Za-z_]\w*)\s*(?:\.\w+)?\s*=[^=]")

EMBEDDED_FRAGMENT_COLOUR = re.compile(r"FragColor\s*=\s*vec[34]\s*\(\s*-?[\d.]")

COLOUR_NAME = re.compile(r"(?i)colou?r|palette|tint|gr[ae]y|rgb|FragColor")

SVG_COLOUR = re.compile(r"#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b|rgba?\([^)]*\)")

DSL_COLOUR = re.compile(r"\bcolou?r\s*=\s*<[^>]*>")

TIMEOUT_SECONDS = 120


def pom_version(artifact):
    """The version the Ixdar root pom pins for an artifact."""
    text = (REPOSITORY / "pom.xml").read_text(encoding="utf-8")
    match = re.search(rf"<artifactId>{artifact}</artifactId>\s*<version>([^<]+)</version>", text)
    if match is None:
        sys.exit(f"the root pom does not pin {artifact}")
    return match.group(1).strip()


def checkstyle_classpath(scratch, autofix_classes):
    """Checkstyle plus autofix-tool, resolved by maven the way maven-checkstyle-plugin does."""
    pom = scratch / "pom.xml"
    pom.write_text(
        CLASSPATH_POM.format(checkstyle=pom_version("checkstyle"), autofix=pom_version("autofix-tool")),
        encoding="utf-8",
    )
    output = scratch / "classpath.txt"
    for offline in (True, False):
        command = ["mvn", "-B", "-q", "dependency:build-classpath", f"-Dmdep.outputFile={output}", "-f", str(pom)]
        if offline:
            command.insert(1, "-o")
        subprocess.run(command, capture_output=True, text=True, timeout=TIMEOUT_SECONDS, check=False)
        if output.is_file() and output.stat().st_size > 0:
            break
    else:
        sys.exit("maven could not resolve checkstyle and autofix-tool")
    entries = [entry for entry in output.read_text(encoding="utf-8").strip().split(":") if entry]
    if autofix_classes:
        entries.insert(0, str(pathlib.Path(autofix_classes).expanduser().resolve()))
    return ":".join(entries)


def java_hits(scratch, autofix_classes):
    """(path, line, kind) of every colour ColorLiteralCheck reports in main Java sources."""
    configuration = scratch / "colour-audit.xml"
    configuration.write_text(AUDIT_CONFIGURATION, encoding="utf-8")
    finished = subprocess.run(
        [
            "java",
            "-cp",
            checkstyle_classpath(scratch, autofix_classes),
            "com.puppycrawl.tools.checkstyle.Main",
            "-c",
            str(configuration),
            *[str(root) for root in JAVA_ROOTS if root.is_dir()],
        ],
        capture_output=True,
        text=True,
        timeout=TIMEOUT_SECONDS,
        check=False,
    )
    hits = []
    for raw in finished.stdout.splitlines():
        match = CHECKSTYLE_VIOLATION.match(raw.strip())
        if match:
            hits.append((pathlib.Path(match.group("path")), int(match.group("line")), match.group("kind")))
    if not hits and finished.returncode not in (0, 1):
        sys.exit(f"checkstyle failed:\n{finished.stdout}{finished.stderr}")
    return hits


def shader_hits_in(path, lines):
    """(path, line, 'shader') of each literal vector a shader source writes as a colour."""
    hits = []
    function = ""
    depth = 0
    for number, line in enumerate(lines, start=1):
        code = line.split("//", 1)[0]
        if depth == 0:
            declared = GLSL_FUNCTION.match(code)
            if declared:
                function = declared.group("name")
        for vector in LITERAL_VECTOR.finditer(code):
            target = None
            for assignment in ASSIGNMENT_TARGET.finditer(code[: vector.start()]):
                target = assignment.group("name")
            if (target and COLOUR_NAME.search(target)) or (function and COLOUR_NAME.search(function)):
                hits.append((path, number, "shader"))
                break
        depth += code.count("{") - code.count("}")
    return hits


def shader_hits():
    """Literal colour vectors in GLSL files and in the shader strings Java builds."""
    hits = []
    for path in sorted((RESOURCES / "glsl").glob("*.[fv]s")):
        hits += shader_hits_in(path, path.read_text(encoding="utf-8").splitlines())
    for root in JAVA_ROOTS:
        for path in sorted(root.rglob("*.java")):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                if EMBEDDED_FRAGMENT_COLOUR.search(line):
                    hits.append((path, number, "shader"))
    return hits


def asset_hits():
    """SVG colours and DSL model colours under resources."""
    hits = []
    for path in sorted(RESOURCES.rglob("*.svg")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            hits += [(path, number, "asset")] * len(SVG_COLOUR.findall(line))
    for path in sorted(RESOURCES.rglob("*.dsl")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            hits += [(path, number, "asset")] * len(DSL_COLOUR.findall(line))
    return hits


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--autofix-classes", help="an uninstalled autofix build's target/classes")
    parser.add_argument("--counts", action="store_true", help="print only the counts by kind")
    arguments = parser.parse_args()
    with tempfile.TemporaryDirectory() as scratch:
        hits = java_hits(pathlib.Path(scratch), arguments.autofix_classes)
    hits += shader_hits() + asset_hits()
    if not arguments.counts:
        for path, line, kind in sorted(hits):
            print(f"{path.relative_to(REPOSITORY)}:{line}: {kind}")
    counts = collections.Counter(kind for _, _, kind in hits)
    print(f"total {len(hits)}: " + ", ".join(f"{kind} {counts[kind]}" for kind in sorted(counts)))


if __name__ == "__main__":
    main()
